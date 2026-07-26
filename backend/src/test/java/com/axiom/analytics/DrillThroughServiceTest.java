package com.axiom.analytics;

import com.axiom.common.NotFoundException;
import com.axiom.security.AccessContext;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drill-through (FR-RPT-006) — the security-critical decision of ADR-008.
 *
 * <p>The scenario every test here builds is the same one the ADR describes: the
 * projection still lists a record as contributing to an aggregate, and the user's
 * access to it has since been withdrawn. A design that trusted the read model
 * would show it. This one asks the authoritative store, now, and shows nothing.
 */
class DrillThroughServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REVOKED = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private JdbcTemplate jdbc;
    private ReportQueryService queries;
    private AuthorizationService authorization;
    private ProjectionStatusService status;
    private DrillThroughService drill;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        authorization = mock(AuthorizationService.class);
        status = mock(ProjectionStatusService.class);
        queries = new ReportQueryService(jdbc, new ReportAccessScope(authorization),
                new QueryGuardrails(), status, authorization);
        drill = new DrillThroughService(jdbc, queries, authorization, status);

        when(authorization.context()).thenReturn(context());
        when(status.stalenessFor(any(UUID.class), any(AnalyticsDataset[].class))).thenReturn(
                new ProjectionStatusService.Staleness(null, 30L, 0, false, "Projected data as of 30s ago."));

        TenantContext.set(new TenantContext.Principal(TENANT, USER, "SALES", "Priya", "p@example.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a record the user has just lost access to is REFUSED, and the projection is never read")
    void refusesARecordTheUserJustLostAccessTo() {
        doThrow(new NotFoundException("No opportunity record with that id is available to you"))
                .when(authorization).requireRead(eq(SecurableObject.OPPORTUNITY), eq(REVOKED));

        assertThatThrownBy(() -> drill.record("OPPORTUNITY", REVOKED))
                .isInstanceOf(NotFoundException.class)
                // 404, not 403: a 403 on an unreadable record confirms it exists, which
                // is the leak the re-check exists to prevent.
                .hasMessageContaining("is available to you");

        // The check happens BEFORE any column is read, and the fact table is not
        // consulted on this path at all — it cannot be, or it would be the authority.
        verify(jdbc, never()).query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("a permitted record is read from the AUTHORITATIVE table, not from the projection")
    void permittedRecordComesFromTheAuthoritativeStore() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(new AuthorizationService.RecordPredicate("true", List.of()));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", REVOKED.toString());
        record.put("name", "Meridian retrofit");
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(record));

        Map<String, Object> found = drill.record("OPPORTUNITY", REVOKED);

        assertThat(found).containsEntry("_source", "AUTHORITATIVE");
        assertThat(found).containsEntry("name", "Meridian retrofit");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(PreparedStatementSetter.class), any(RowMapper.class));
        assertThat(sql.getValue()).contains("from sales.opportunity t");
        assertThat(sql.getValue()).doesNotContain("analytics.opportunity_fact");
    }

    @Test
    @DisplayName("candidates the projection lists but the store refuses are DROPPED and counted, not padded")
    void droppedCandidatesAreReportedRatherThanBackfilled() {
        when(authorization.visibleRecordPredicate(eq(SecurableObject.OPPORTUNITY), anyString()))
                .thenReturn(new AuthorizationService.RecordPredicate("(t.owner_id = ?)", List.of(USER)));

        UUID permitted = UUID.randomUUID();
        // Step 1: the read model names three candidates.
        when(jdbc.query(anyString(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(List.of(permitted, REVOKED, UUID.randomUUID()));
        // Step 2: the authoritative store, under the live predicate, permits one.
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", permitted.toString());
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(record));

        DrillThroughService.DrillResult result = drill.records(
                new ReportQueryService.ReportRequest("OPPORTUNITY", "TABULAR", null, null, null,
                        null, null, null, null, null, null), 50);

        assertThat(result.projectedCandidates()).isEqualTo(3);
        assertThat(result.returned()).isEqualTo(1);
        // Reported. Padding the page from the projection would reintroduce the read
        // model as an authority on visibility, one row further down.
        assertThat(result.droppedByRecheck()).isEqualTo(2);
        assertThat(result.note()).contains("permission check taken now");
    }

    @Test
    @DisplayName("a dataset with no authoritative object cannot be drilled at all")
    void datasetWithoutASecurableObjectIsRefused() {
        assertThatThrownBy(() -> drill.record("ACTIVITY", REVOKED))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Drill-through is not available");
    }

    @Test
    @DisplayName("a field the profile hides never leaves the database — it is not selected at all")
    void hiddenFieldIsNotEvenSelected() {
        when(authorization.context()).thenReturn(context(Set.of("taxId")));
        when(authorization.visibleRecordPredicate(eq(SecurableObject.ACCOUNT), anyString()))
                .thenReturn(new AuthorizationService.RecordPredicate("true", List.of()));
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(RowMapper.class)))
                .thenReturn(List.of(Map.of("id", REVOKED.toString())));

        drill.record("ACCOUNT", REVOKED);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(PreparedStatementSetter.class), any(RowMapper.class));
        // Not fetched and nulled — never fetched. A withheld value that reaches the
        // JVM is in a heap dump and a query log for no benefit.
        assertThat(sql.getValue()).doesNotContain("tax_id");
        assertThat(sql.getValue()).contains("t.name");
    }

    private static AccessContext context() {
        return context(Set.of());
    }

    private static AccessContext context(Set<String> hiddenOnAccount) {
        return new AccessContext(TENANT, USER, "SALES", false, false, null, null, null,
                List.of(), Set.of(), Map.of(),
                hiddenOnAccount.isEmpty() ? Map.of() : Map.of(SecurableObject.ACCOUNT, hiddenOnAccount),
                Map.of(), null, null, null, List.of(), List.of());
    }
}
