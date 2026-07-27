package com.axiom.migration;

import com.axiom.migration.MigrationModel.RunHandle;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationRecoveryServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PLAN = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID RUN = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private MigrationRunService runs;
    private JdbcTemplate jdbc;
    private MigrationRecoveryService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext.Principal(TENANT, USER, "TENANT_ADMIN",
                "Tenant Admin", "admin@example.test"));
        runs = mock(MigrationRunService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new MigrationRecoveryService(jdbc, runs,
                mock(MigrationReconciler.class));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void queuedRunCanOnlyBeCancelledBeforeExecution() {
        when(runs.run(RUN)).thenReturn(run("IMPORT", "QUEUED", 0, 0));
        var view = service.recovery(RUN);
        assertThat(view.allowedActions()).containsExactly("CANCEL");
        assertThat(view.targetWritesCommitted()).isFalse();
        assertThat(view.nextStep()).contains("before execution");
    }

    @Test void runningRunCannotBePartiallyCancelled() {
        when(runs.run(RUN)).thenReturn(run("IMPORT", "RUNNING", 0, 0));
        var view = service.recovery(RUN);
        assertThat(view.allowedActions()).isEmpty();
        assertThat(view.nextStep()).contains("atomically");
    }

    @Test void failedRunOffersLinkedRetryWithoutClaimingWritesCommitted() {
        when(runs.run(RUN)).thenReturn(run("DELTA", "FAILED", 2, 0));
        var view = service.recovery(RUN);
        assertThat(view.allowedActions()).containsExactly("RETRY");
        assertThat(view.targetWritesCommitted()).isFalse();
        assertThat(view.checkpointAdvanced()).isFalse();
    }

    @Test void successfulImportOffersReconcileAndRollback() {
        when(runs.run(RUN)).thenReturn(run("IMPORT", "COMPLETED", 10, 1));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("delta_checkpoint"),
                org.mockito.ArgumentMatchers.eq(Long.class),
                org.mockito.ArgumentMatchers.eq(TENANT), org.mockito.ArgumentMatchers.eq(RUN)))
                .thenReturn(5L);
        var view = service.recovery(RUN);
        assertThat(view.allowedActions()).containsExactly("RECONCILE", "ROLLBACK");
        assertThat(view.targetWritesCommitted()).isTrue();
        assertThat(view.checkpointAdvanced()).isTrue();
    }

    @Test void successfulRollbackDoesNotOfferAnotherDestructiveAction() {
        when(runs.run(RUN)).thenReturn(run("ROLLBACK", "COMPLETED", 0, 0));
        var view = service.recovery(RUN);
        assertThat(view.allowedActions()).isEmpty();
        assertThat(view.nextStep()).contains("evidence");
    }

    @Test void partialRollbackQueuesCurrentLedgerInsteadOfRetryingCompletedAttempt() {
        when(runs.run(RUN)).thenReturn(run("ROLLBACK", "COMPLETED", 0, 0, 4, 9));
        var view = service.recovery(RUN);
        assertThat(view.allowedActions()).containsExactly("ROLLBACK");
        assertThat(view.nextStep()).contains("remain blocked");
    }

    private static RunHandle run(String mode, String status, long created, long updated) {
        return run(mode, status, created, updated, 0, 0);
    }

    private static RunHandle run(String mode, String status, long created, long updated,
                                 long removed, long issues) {
        Instant now = Instant.parse("2026-07-26T10:00:00Z");
        return new RunHandle(RUN, PLAN, mode, status, "TEST", created + updated, created + updated,
                "COMPLETED".equals(status) ? 100 : 0, created, updated, 0, removed, issues,
                null, 1, now, "QUEUED".equals(status) ? null : now,
                "COMPLETED".equals(status) || "FAILED".equals(status) ? now : null, "test");
    }
}
