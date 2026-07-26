package com.axiom.migration;

import com.axiom.audit.AuditService;
import com.axiom.migration.MigrationConnectionService.ConnectionRow;
import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ObjectOutcome;
import com.axiom.migration.MigrationModel.PlanContext;
import com.axiom.migration.MigrationModel.PreFlightReport;
import com.axiom.migration.MigrationRollbackService.RollbackResult;
import com.axiom.migration.SourceContract.SourceAdapter;
import com.axiom.migration.SourceContract.SourceSession;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The worker tier (system-design §3.3).
 *
 * <h2>Why a migration is never an HTTP request</h2>
 * system-design §3.3 lists the migration engine as an extraction candidate
 * precisely because it is long-running, resource-hungry and episodic. A request
 * that ran an import would hold a connection and a thread for the duration, time
 * out in front of the operator at the least recoverable moment, and give no way
 * to ask "how far did it get". So {@link MigrationRunService} writes a QUEUED row
 * and returns a job handle in milliseconds, and everything that touches source
 * or target data happens here, on a poller, with the tenant bound by
 * {@link SystemTaskRunner}.
 *
 * <h2>Transaction shape, stated honestly</h2>
 * Each phase — claim, execute, finish — is its own transaction. That is what
 * makes a failure recordable: if the execute transaction rolls back, the run row
 * is not rolled back with it, so the failure lands in the database as FAILED
 * with a message instead of leaving the run QUEUED to be retried forever.
 *
 * <p>The execute phase is a single transaction for the whole run: a partially
 * imported migration is worse than a failed one, because the operator then has
 * to work out which half landed. The consequence is that in-flight progress is
 * reported at run granularity (QUEUED to RUNNING to COMPLETED with totals) rather
 * than per record. For the volumes a single fixture or a mid-market source
 * produces that is the right trade; a source large enough to need per-batch
 * progress would need chunked commits and a resumable cursor, which is a change
 * to this class and not to the rest of the engine.
 */
@Service
public class MigrationWorker {

    private static final Logger log = LoggerFactory.getLogger(MigrationWorker.class);

    private final JdbcTemplate jdbc;
    private final SystemTaskRunner tasks;
    private final MigrationPlanService plans;
    private final MigrationConnectionService connections;
    private final SourceAdapterRegistry adapters;
    private final MigrationAnalyzer analyzer;
    private final MigrationImporter importer;
    private final MigrationRollbackService rollback;
    private final MigrationReconciler reconciler;
    private final AuditService audit;
    private final boolean enabled;

    public MigrationWorker(JdbcTemplate jdbc, SystemTaskRunner tasks, MigrationPlanService plans,
                           MigrationConnectionService connections, SourceAdapterRegistry adapters,
                           MigrationAnalyzer analyzer, MigrationImporter importer,
                           MigrationRollbackService rollback, MigrationReconciler reconciler,
                           AuditService audit,
                           @Value("${axiom.migration.worker-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.plans = plans;
        this.connections = connections;
        this.adapters = adapters;
        this.analyzer = analyzer;
        this.importer = importer;
        this.rollback = rollback;
        this.reconciler = reconciler;
        this.audit = audit;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${axiom.migration.poll-fixed-delay-ms:1500}")
    public void tick() {
        if (!enabled) return;
        for (UUID tenantId : tasks.tenantIds()) {
            try {
                drainTenant(tenantId);
            } catch (RuntimeException ex) {
                log.warn("Migration worker tick failed for tenant {}: {}", tenantId, ex.getMessage());
            }
        }
    }

    /** @return the run advanced, or null when the tenant has nothing queued. */
    public UUID drainTenant(UUID tenantId) {
        AtomicReference<UUID> claimed = new AtomicReference<>();
        tasks.inTenant(tenantId, t -> {
            claimed.set(claim());
            return claimed.get() == null ? 0 : 1;
        });
        UUID runId = claimed.get();
        if (runId == null) return null;

        try {
            tasks.inTenant(tenantId, t -> {
                execute(runId);
                return 1;
            });
        } catch (RuntimeException ex) {
            log.error("Migration run {} failed", runId, ex);
            tasks.inTenant(tenantId, t -> {
                fail(runId, ex.getMessage());
                return 1;
            });
        }
        return runId;
    }

    // ------------------------------------------------------------------ phases

    // These three are called from drainTenant on this same bean, so a
    // @Transactional annotation on them would be self-invocation and do nothing.
    // Their transaction — and the app.tenant_id binding the RLS policies need —
    // comes from SystemTaskRunner.inTenant, which is the point of that class.

    /** Take the oldest queued run for this tenant and mark it RUNNING. */
    UUID claim() {
        UUID tenantId = TenantContext.get().tenantId();
        List<UUID> queued = jdbc.query("""
                select id from migration.run
                where tenant_id = ? and status = 'QUEUED'
                order by queued_at
                limit 1
                """, (rs, i) -> rs.getObject(1, UUID.class), tenantId);
        if (queued.isEmpty()) return null;
        UUID runId = queued.get(0);
        jdbc.update("""
                update migration.run set status = 'RUNNING', phase = 'EXTRACT', started_at = now()
                where tenant_id = ? and id = ?
                """, tenantId, runId);
        return runId;
    }

    void execute(UUID runId) {
        UUID tenantId = TenantContext.get().tenantId();
        RunRow run = run(tenantId, runId);

        switch (run.mode()) {
            case "DRY_RUN" -> dryRun(runId, run);
            case "IMPORT", "DELTA" -> importRun(runId, run);
            case "ROLLBACK" -> rollbackRun(runId, run);
            default -> throw new IllegalStateException("Unknown migration run mode " + run.mode());
        }
    }

    private void dryRun(UUID runId, RunRow run) {
        PlanContext plan = plans.context(run.planId());
        Adapter adapter = adapter(run.planId());

        PreFlightReport report = analyzer.analyse(plan, adapter.adapter(), adapter.session(), run.deltaSince());
        writeIssues(runId, report.issues());
        reconciler.persist(runId, run.planId(), report.objects(), true);

        finish(runId, "SUMMARISE", report.totalToCreate(), report.totalToUpdate(), report.totalToSkip(), 0,
                report.issues().size(), report.objects().stream().mapToLong(ObjectOutcome::sourceCount).sum(),
                null,
                "Dry run complete. Nothing was written. " + report.totalToCreate() + " record(s) would be created, "
                + report.totalToUpdate() + " updated, " + report.totalToSkip() + " skipped; "
                + report.unmappedFields().size() + " source field(s) have no Axiom destination.");

        audit.record("MIGRATION_DRY_RUN", "MIGRATION_PLAN", run.planId(),
                "Dry run for plan \"" + plan.planName() + "\" — no records written",
                Map.of("runId", runId.toString(),
                        "wouldCreate", String.valueOf(report.totalToCreate()),
                        "wouldSkip", String.valueOf(report.totalToSkip()),
                        "unmappedFields", String.valueOf(report.unmappedFields().size())));
    }

    private void importRun(UUID runId, RunRow run) {
        PlanContext plan = plans.context(run.planId());
        Adapter adapter = adapter(run.planId());
        UUID tenantId = TenantContext.get().tenantId();

        MigrationImporter.Outcome outcome = importer.execute(runId, plan, adapter.adapter(),
                adapter.session(), run.deltaSince());
        writeIssues(runId, outcome.issues());
        reconciler.persist(runId, run.planId(), outcome.outcomes(), false);

        jdbc.update("""
                update migration.plan
                   set status = 'IMPORTED',
                       imported_at = coalesce(imported_at, now()),
                       delta_watermark = coalesce(?, delta_watermark),
                       updated_at = now()
                 where tenant_id = ? and id = ?
                """, MigrationImporter.timestamp(outcome.sourceWatermark()), tenantId, run.planId());

        finish(runId, "RECONCILE", outcome.created(), outcome.updated(), outcome.skipped(), 0,
                outcome.issues().size(),
                outcome.outcomes().stream().mapToLong(ObjectOutcome::sourceCount).sum(),
                outcome.sourceWatermark(),
                ("DELTA".equals(run.mode()) ? "Delta re-sync" : "Import") + " complete. "
                + outcome.created() + " created, " + outcome.updated() + " updated, "
                + outcome.skipped() + " not migrated (see the reconciliation report for every reason).");

        audit.record("DELTA".equals(run.mode()) ? "MIGRATION_DELTA_RESYNC" : "MIGRATION_IMPORT",
                "MIGRATION_PLAN", run.planId(),
                ("DELTA".equals(run.mode()) ? "Delta re-sync" : "Import") + " for plan \"" + plan.planName()
                + "\": " + outcome.created() + " created, " + outcome.updated() + " updated, "
                + outcome.skipped() + " skipped",
                Map.of("runId", runId.toString(),
                        "created", String.valueOf(outcome.created()),
                        "updated", String.valueOf(outcome.updated()),
                        "skipped", String.valueOf(outcome.skipped())));
    }

    private void rollbackRun(UUID runId, RunRow run) {
        RollbackResult result = rollback.execute(runId, run.planId());
        List<Issue> issues = new ArrayList<>(result.removedRecords());
        issues.addAll(result.blocked());
        writeIssues(runId, issues);

        finish(runId, "REMOVE", 0, 0, 0, result.removed(), issues.size(), result.removed(), null,
                "Rollback complete. " + result.removed() + " record(s) created by this migration were removed"
                + (result.blocked().isEmpty() ? "" : "; " + result.blocked().size()
                    + " could not be removed and are listed with the reason")
                + ". Records that existed before the migration were not touched.");
    }

    // ------------------------------------------------------------------ run row plumbing

    record RunRow(UUID id, UUID planId, String mode, String status, Instant deltaSince) {}

    private RunRow run(UUID tenantId, UUID runId) {
        List<RunRow> rows = jdbc.query("""
                select id, plan_id, mode, status, delta_since from migration.run
                where tenant_id = ? and id = ?
                """, (rs, i) -> new RunRow(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                        rs.getString("mode"), rs.getString("status"),
                        rs.getTimestamp("delta_since") == null ? null : rs.getTimestamp("delta_since").toInstant()),
                tenantId, runId);
        if (rows.isEmpty()) throw new IllegalStateException("Migration run " + runId + " disappeared mid-flight");
        return rows.get(0);
    }

    private record Adapter(SourceAdapter adapter, SourceSession session) {}

    private Adapter adapter(UUID planId) {
        ConnectionRow connection = connections.connection(plans.plan(planId).connectionId());
        return new Adapter(adapters.require(connection.vendor()), connections.session(connection));
    }

    private void writeIssues(UUID runId, List<Issue> issues) {
        UUID tenantId = TenantContext.get().tenantId();
        for (Issue issue : issues) {
            jdbc.update("""
                    insert into migration.run_issue
                      (tenant_id, run_id, severity, category, source_object, source_record_id, source_label,
                       field_name, related_object, related_record_id, related_label, reason)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, runId, issue.severity(), issue.category(), issue.sourceObject(),
                    issue.sourceRecordId(), issue.sourceLabel(), issue.fieldName(), issue.relatedObject(),
                    issue.relatedRecordId(), issue.relatedLabel(), issue.reason());
        }
    }

    private void finish(UUID runId, String phase, long created, long updated, long skipped, long removed,
                        long issueCount, long totalUnits, Instant watermark, String message) {
        jdbc.update("""
                update migration.run
                   set status = 'COMPLETED', phase = ?, finished_at = now(),
                       total_units = ?, processed_units = ?,
                       records_created = ?, records_updated = ?, records_skipped = ?, records_removed = ?,
                       issue_count = ?, source_watermark = coalesce(?, source_watermark), message = ?
                 where tenant_id = ? and id = ?
                """, phase, totalUnits, totalUnits, created, updated, skipped, removed, issueCount,
                MigrationImporter.timestamp(watermark), message, TenantContext.get().tenantId(), runId);
    }

    void fail(UUID runId, String message) {
        jdbc.update("""
                update migration.run set status = 'FAILED', finished_at = now(), message = ?
                where tenant_id = ? and id = ?
                """, message == null ? "Migration run failed" : message,
                TenantContext.get().tenantId(), runId);
    }
}
