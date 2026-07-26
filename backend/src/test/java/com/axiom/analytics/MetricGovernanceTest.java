package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-RPT-009: "a metric with more than one active definition is a DEFECT, not a
 * configuration choice."
 *
 * <p>The rule is enforced by a partial unique index — {@code
 * uq_metric_definition_single_active} — so what is under test here is not the check
 * (there is deliberately no check; a read-then-write check is a race two concurrent
 * administrators would win). It is that the database's refusal reaches the caller
 * as an explanation of the rule and of the governed alternative, rather than as a
 * 500 with a constraint name in it.
 */
class MetricGovernanceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222221");
    private static final UUID DRAFT_ID = UUID.fromString("66666666-6666-6666-6666-666666666661");
    private static final UUID ACTIVE_ID = UUID.fromString("66666666-6666-6666-6666-666666666662");

    private JdbcTemplate jdbc;
    private AuditService audit;
    private MetricRegistryService registry;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        registry = new MetricRegistryService(jdbc, audit);
        TenantContext.set(new TenantContext.Principal(TENANT, ADMIN, "TENANT_ADMIN",
                "Raj", "raj@example.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MetricRegistryService.MetricDefinition definition(UUID id, int version, String statusText) {
        return new MetricRegistryService.MetricDefinition(id, "WIN_RATE", "Win rate", version,
                "closed won / (closed won + closed lost)", "count basis", "PERCENT", null,
                "FR-FCT-010", "docs/product/14-reporting-and-analytics.md §3", statusText,
                null, null, null, null);
    }

    @Test
    @DisplayName("a SECOND active definition for one metric is REJECTED")
    void secondActiveDefinitionIsRejected() {
        // byId -> the draft; then activeOrNull -> the incumbent, after the write fails.
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(definition(DRAFT_ID, 2, "DRAFT")))
                .thenReturn(List.of(definition(ACTIVE_ID, 1, "ACTIVE")));
        // The database refuses: uq_metric_definition_single_active.
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uq_metric_definition_single_active\""));

        assertThatThrownBy(() -> registry.activate(DRAFT_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("WIN_RATE already has an active definition")
                .hasMessageContaining("(v1)")
                // The requirement is quoted, because an administrator who sees only
                // "constraint violated" will go looking for a way around it.
                .hasMessageContaining("more than one active definition is a defect")
                // And the governed alternative is named.
                .hasMessageContaining("Publish this as a new version")
                .hasMessageContaining("its own metric code");
    }

    @Test
    @DisplayName("publishing a new version retires the incumbent in the same transaction")
    void publishRetiresTheIncumbentFirst() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(definition(ACTIVE_ID, 1, "ACTIVE")))   // activeOrNull
                .thenReturn(List.of(definition(DRAFT_ID, 2, "ACTIVE")));   // byId after insert
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(DRAFT_ID);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        MetricRegistryService.MetricDefinition published =
                registry.publishNewVersion(new MetricRegistryService.DefinitionRequest(
                        "win-rate", "Win rate", "won / (won + lost)", "count basis", "PERCENT",
                        null, "FR-FCT-010", "doc 14 §3", "value-weighted variant requested"));

        assertThat(published.version()).isEqualTo(2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("set status = 'RETIRED'");

        // The insert names an explicitly incremented version. Formulas are never
        // edited in place: a figure quoted last quarter must stay reproducible under
        // the definition that produced it.
        ArgumentCaptor<String> insert = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).queryForObject(insert.capture(), eq(UUID.class),
                any(Object[].class));
        assertThat(insert.getValue()).contains("insert into analytics.metric_definition")
                .contains("'ACTIVE'");
        assertThat(insert.getValue()).doesNotContain("update analytics.metric_definition set formula");
    }

    @Test
    @DisplayName("metric codes are normalised, so win-rate and WIN_RATE are the same governed metric")
    void metricCodesAreNormalised() {
        assertThat(MetricRegistryService.normalise("win-rate")).isEqualTo("WIN_RATE");
        assertThat(MetricRegistryService.normalise(" Win_Rate ")).isEqualTo("WIN_RATE");
    }

    @Test
    @DisplayName("publishing requires an administrator — a sales user cannot redefine a governed metric")
    void publishingIsAdministratorGated() {
        TenantContext.set(new TenantContext.Principal(TENANT, ADMIN, "SALES", "Priya", "p@example.com"));
        assertThatThrownBy(() -> registry.publishNewVersion(new MetricRegistryService.DefinitionRequest(
                "WIN_RATE", "Win rate", "anything", null, null, null, null, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("activating an already-active definition is a no-op, not a duplicate write")
    void activatingAnActiveDefinitionIsIdempotent() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(definition(ACTIVE_ID, 1, "ACTIVE")));

        MetricRegistryService.MetricDefinition result = registry.activate(ACTIVE_ID);

        assertThat(result.status()).isEqualTo("ACTIVE");
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .update(anyString(), any(Object[].class));
    }
}
