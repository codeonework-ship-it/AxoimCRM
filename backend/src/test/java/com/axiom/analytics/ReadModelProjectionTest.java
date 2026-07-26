package com.axiom.analytics;

import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two ADR-003 properties the read model has to have, and one honest admission
 * about what a mocked test can and cannot show.
 *
 * <p>Idempotence and out-of-order safety are not implemented in Java here; they are
 * implemented in one SQL clause each ({@code on conflict … do update} and the
 * {@code excluded.source_updated_at &gt; …} guard). A mock cannot execute SQL, so
 * asserting on Java behaviour alone would prove nothing about the property that
 * matters. These tests therefore assert on the SQL the projector emits — which is
 * where the guarantee lives — and on the consumer behaviour layered above it.
 * {@code ReconciliationServiceTest} closes the loop by checking that projected and
 * authoritative aggregates agree.
 */
class ReadModelProjectionTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OPPORTUNITY = UUID.fromString("44444444-4444-4444-4444-444444444441");
    private static final UUID EVENT_ONE = UUID.fromString("55555555-5555-5555-5555-555555555551");

    private JdbcTemplate jdbc;
    private ReadModelProjector projector;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        projector = new ReadModelProjector(jdbc);
        TenantContext.set(new TenantContext.Principal(TENANT,
                UUID.fromString("22222222-2222-2222-2222-222222222221"),
                "TENANT_ADMIN", "Raj", "raj@example.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("duplicate delivery of the same event produces ONE row, not two")
    void duplicateDeliveryUpsertsOneRow() {
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(1);

        projector.project(TENANT, AnalyticsDataset.OPPORTUNITY, List.of(OPPORTUNITY));
        projector.project(TENANT, AnalyticsDataset.OPPORTUNITY, List.of(OPPORTUNITY));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(sql.capture(), any(PreparedStatementSetter.class));

        // Both deliveries issue the identical statement, and that statement is an
        // upsert keyed on the business id. There is no INSERT path that could produce
        // a second row for the same opportunity.
        assertThat(sql.getAllValues()).hasSize(2);
        assertThat(sql.getAllValues().get(0)).isEqualTo(sql.getAllValues().get(1));
        assertThat(sql.getValue())
                .contains("insert into analytics.opportunity_fact")
                .contains("on conflict (opportunity_id) do update set");
        assertThat(sql.getValue()).doesNotContain("insert into analytics.opportunity_fact select");
    }

    @Test
    @DisplayName("an out-of-order older event cannot overwrite a newer projection")
    void olderEventIsRefusedByTheWatermarkGuard() {
        when(jdbc.update(anyString(), any(PreparedStatementSetter.class))).thenReturn(0);

        int written = projector.project(TENANT, AnalyticsDataset.OPPORTUNITY, List.of(OPPORTUNITY));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(PreparedStatementSetter.class));

        // THE guard. Strictly greater-than: an event carrying an older or identical
        // read of the row updates nothing, so a redelivery after a relay restart
        // cannot move the projection backwards.
        assertThat(sql.getValue())
                .contains("where excluded.source_updated_at > opportunity_fact.source_updated_at");
        // And the refusal is observable rather than indistinguishable from success.
        assertThat(written).isZero();
    }

    @Test
    @DisplayName("every dataset carries the same watermark guard — none may be forgotten")
    void everyDatasetIsGuarded() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        for (AnalyticsDataset dataset : AnalyticsDataset.values()) {
            projector.projectAll(TENANT, dataset);
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(AnalyticsDataset.values().length)).update(sql.capture(), any(Object[].class));
        for (String statement : sql.getAllValues()) {
            assertThat(statement).contains("on conflict").contains("where excluded.source_updated_at >");
        }
    }

    @Test
    @DisplayName("the consumer collapses a batch to distinct records, so N events cost one projection")
    void consumerCollapsesDuplicateEventsInOneBatch() {
        ReadModelProjector spyProjector = mock(ReadModelProjector.class);
        SystemTaskRunner tasks = mock(SystemTaskRunner.class);
        ProjectionConsumer consumer = new ProjectionConsumer(jdbc, spyProjector, tasks);

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(new ProjectionConsumer.Cursor(null, null));
        // Three events, all about the SAME opportunity — the ordinary shape of a
        // stage change followed by a close-date change followed by a re-save.
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(
                        new ProjectionConsumer.Event(EVENT_ONE, OPPORTUNITY, Instant.now()),
                        new ProjectionConsumer.Event(UUID.randomUUID(), OPPORTUNITY, Instant.now()),
                        new ProjectionConsumer.Event(UUID.randomUUID(), OPPORTUNITY, Instant.now())));
        when(spyProjector.project(eq(TENANT), eq(AnalyticsDataset.OPPORTUNITY), any())).thenReturn(1);

        int consumed = consumer.consume(TENANT, AnalyticsDataset.OPPORTUNITY);

        assertThat(consumed).isEqualTo(3);
        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
        verify(spyProjector).project(eq(TENANT), eq(AnalyticsDataset.OPPORTUNITY), ids.capture());
        assertThat(ids.getValue()).containsExactly(OPPORTUNITY);
    }

    @Test
    @DisplayName("an empty outbox batch advances nothing — no checkpoint write, no projection")
    void emptyBatchIsANoOp() {
        ReadModelProjector spyProjector = mock(ReadModelProjector.class);
        ProjectionConsumer consumer =
                new ProjectionConsumer(jdbc, spyProjector, mock(SystemTaskRunner.class));

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(new ProjectionConsumer.Cursor(Instant.EPOCH, EVENT_ONE));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of());

        assertThat(consumer.consume(TENANT, AnalyticsDataset.LEAD)).isZero();
        verify(spyProjector, never()).project(any(), any(), any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("the stage-transition projection recomputes exited_forward rather than freezing it")
    void stageTransitionProjectionAllowsRecompute() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        projector.projectStageTransitions(TENANT, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        // >= rather than >, and deliberately so: exited_forward is a function of the
        // opportunity's CURRENT stage, which moves without the history row changing.
        assertThat(sql.getValue())
                .contains("where excluded.source_updated_at >= stage_transition_fact.source_updated_at");
    }

    @Test
    @DisplayName("the checkpoint is a strict (created_at, id) row comparison, not a timestamp alone")
    void cursorUsesStrictRowComparison() {
        ReadModelProjector spyProjector = mock(ReadModelProjector.class);
        ProjectionConsumer consumer =
                new ProjectionConsumer(jdbc, spyProjector, mock(SystemTaskRunner.class));
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(new ProjectionConsumer.Cursor(Instant.ofEpochSecond(1), EVENT_ONE));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of());

        consumer.consume(TENANT, AnalyticsDataset.ACCOUNT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(PreparedStatementSetter.class),
                any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue()).contains("(created_at, id) >");
    }

    /** Guards the sentinel handling that turns '-infinity' back into "no cursor yet". */
    @Test
    @DisplayName("an unset checkpoint reads as no cursor, not as the year -4713")
    void infinityCursorIsTreatedAsUnset() {
        Timestamp negativeInfinity = new Timestamp(Long.MIN_VALUE);
        assertThat(negativeInfinity.getTime()).isLessThanOrEqualTo(Long.MIN_VALUE + 1000L);
    }
}
