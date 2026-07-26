package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshot immutability (ADR-008 decision 2) and scheduled reconciliation
 * (ADR-008 Compliance).
 *
 * <p>These two are tested together because they are the same argument at different
 * time scales: a snapshot is a number that must not change after the fact, and
 * reconciliation is the standing check that the numbers which <em>are</em> allowed
 * to change have not drifted away from the records they describe.
 */
class SnapshotAndReconciliationTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("22222222-2222-2222-2222-222222222221");

    private JdbcTemplate jdbc;
    private SystemTaskRunner tasks;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(SystemTaskRunner.class);
        audit = mock(AuditService.class);
        TenantContext.set(new TenantContext.Principal(TENANT, ADMIN, "TENANT_ADMIN",
                "Raj", "raj@example.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ immutability

    @Test
    @DisplayName("a snapshot is never updated — the service issues INSERTs only")
    void snapshotsAreOnlyEverInserted() {
        SnapshotService snapshots = new SnapshotService(jdbc, tasks, audit);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        snapshots.capture(TENANT, "SCHEDULED");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("insert into analytics.pipeline_snapshot");
        // No path in this class can edit history. The database refuses UPDATE with a
        // trigger and withholds the grant as well; this asserts the service never
        // even attempts it.
        for (String statement : sql.getAllValues()) {
            assertThat(statement).doesNotContain("update analytics.pipeline_snapshot");
            assertThat(statement).doesNotContain("update analytics.forecast_snapshot");
        }
    }

    @Test
    @DisplayName("capturing the same day twice is REFUSED rather than doubling the history")
    void secondCaptureForTheSameDayIsRefused() {
        SnapshotService snapshots = new SnapshotService(jdbc, tasks, audit);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uq_pipeline_snapshot_day\""));

        assertThatThrownBy(() -> snapshots.capture(TENANT, "SCHEDULED"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists for today")
                .hasMessageContaining("immutable")
                // The alternative is named rather than leaving the operator stuck.
                .hasMessageContaining("different capture reason");
    }

    @Test
    @DisplayName("the retention sweep announces itself to the immutability trigger before deleting")
    void retentionSweepAnnouncesItself() {
        SnapshotService snapshots = new SnapshotService(jdbc, tasks, audit);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("snapshot_type", "PIPELINE", "retain_days", 730)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        snapshots.sweepRetention(TENANT);

        ArgumentCaptor<String> executed = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(executed.capture());
        // SET LOCAL, so the permission to delete lasts exactly one transaction. Any
        // other DELETE against a snapshot table hits the trigger and fails.
        assertThat(executed.getValue()).isEqualTo("set local app.snapshot_retention_sweep = 'on'");
    }

    @Test
    @DisplayName("capture requires an administrator")
    void captureIsAdministratorGated() {
        SnapshotService snapshots = new SnapshotService(jdbc, tasks, audit);
        TenantContext.set(new TenantContext.Principal(TENANT, ADMIN, "SALES", "Priya", "p@example.com"));
        assertThatThrownBy(() -> snapshots.captureNow("MANUAL"))
                .isInstanceOf(com.axiom.common.ForbiddenException.class);
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    @DisplayName("a projected aggregate that equals authoritative recomputation reports ZERO drift")
    void reconciliationReportsZeroDriftWhenTheyAgree() {
        ReconciliationService service = new ReconciliationService(jdbc, tasks, audit);
        // Every check asks for the projected figure and then the authoritative one.
        // Returning the same value for both is the healthy case.
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("2500800.00"));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ReconciliationService.ReconciliationReport report = service.reconcile(TENANT);

        assertThat(report.checksRun()).isGreaterThanOrEqualTo(8);
        assertThat(report.checksDrifted()).isZero();
        assertThat(report.totalAbsoluteDrift()).isEqualByComparingTo("0");
        assertThat(report.verdict()).contains("zero drift");
        assertThat(report.checks()).allSatisfy(check -> assertThat(check.status()).isEqualTo("MATCH"));
    }

    @Test
    @DisplayName("a drifted aggregate is reported with the difference and what to do about it")
    void reconciliationDetectsDrift() {
        ReconciliationService service = new ReconciliationService(jdbc, tasks, audit);
        // Projected 9 rows, authoritative 10: a record the projection never saw.
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(new BigDecimal("9"), new BigDecimal("10"));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ReconciliationService.ReconciliationReport report = service.reconcile(TENANT);

        ReconciliationService.CheckResult first = report.checks().get(0);
        assertThat(first.status()).isEqualTo("DRIFT");
        assertThat(first.drift()).isEqualByComparingTo("-1");
        assertThat(first.driftPct()).isEqualByComparingTo("-0.1");
        // The remedy is stated, and so is how to tell staleness from a real bug.
        assertThat(first.detail()).contains("Run a backfill")
                .contains("if the drift survives a rebuild the projection SQL is wrong, not stale");
        assertThat(report.checksDrifted()).isPositive();
    }

    @Test
    @DisplayName("every drift observation is stored, so a scheduled run leaves evidence")
    void driftObservationsArePersisted() {
        ReconciliationService service = new ReconciliationService(jdbc, tasks, audit);
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(BigDecimal.ONE);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ReconciliationService.ReconciliationReport report = service.reconcile(TENANT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(report.checksRun()))
                .update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("insert into analytics.reconciliation_run");
    }

    @Test
    @DisplayName("the authoritative side is recomputed with its own SQL, never the projection's")
    void authoritativeSideDoesNotReuseProjectionSql() {
        ReconciliationService service = new ReconciliationService(jdbc, tasks, audit);
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), any(Object[].class)))
                .thenReturn(BigDecimal.ZERO);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.reconcile(TENANT);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce())
                .queryForObject(sql.capture(), eq(BigDecimal.class), any(Object[].class));
        List<String> statements = sql.getAllValues();
        // Half the statements read the read model, half read the OLTP tables — and no
        // statement reads both, which is what stops the check agreeing with itself.
        assertThat(statements).anySatisfy(s -> assertThat(s).contains("analytics.opportunity_fact"));
        assertThat(statements).anySatisfy(s -> assertThat(s).contains("sales.opportunity"));
        assertThat(statements).allSatisfy(s -> assertThat(
                s.contains("analytics.") && s.contains("sales.opportunity")).isFalse());
    }
}
