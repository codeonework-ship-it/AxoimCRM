package com.axiom.migration;

import com.axiom.api.PageResult;
import com.axiom.auth.CrmRole;
import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ReconciliationReport;
import com.axiom.migration.MigrationModel.RunHandle;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Operator-facing recovery read model for E18.
 *
 * It turns raw run states into explicit safe actions. Operators never have to
 * infer whether Retry means mutating a failed attempt (it does not), whether a
 * running import can be cancelled half-way (it cannot), or whether a delta
 * checkpoint advanced after failure (it does not).
 */
@Service
public class MigrationRecoveryService {

    public static final int PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final MigrationRunService runs;
    private final MigrationReconciler reconciler;

    public MigrationRecoveryService(JdbcTemplate jdbc, MigrationRunService runs,
                                    MigrationReconciler reconciler) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.reconciler = reconciler;
    }

    public record RecoveryView(RunHandle run, List<String> allowedActions, String nextStep,
                               boolean targetWritesCommitted, boolean checkpointAdvanced,
                               ReconciliationReport reconciliation) {}

    public record RecoveryActionRow(UUID id, UUID runId, String action, String status, String reason,
                                    UUID resultRunId, String detail, Instant createdAt) {}

    public record DeltaCheckpointRow(String sourceObject, Instant watermark, UUID lastSuccessRunId,
                                     long recordsCreated, long recordsUpdated, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public RecoveryView recovery(UUID runId) {
        RunHandle run = runs.run(runId);
        List<String> actions = new ArrayList<>();
        String next;
        if ("QUEUED".equals(run.status())) {
            actions.add("CANCEL");
            next = "The run is waiting for the worker. You may cancel it before execution begins.";
        } else if ("RUNNING".equals(run.status())) {
            next = "The run is executing atomically. Wait for completion or failure; partial cancellation is disabled.";
        } else if ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status())) {
            actions.add("RETRY");
            next = "Review the issue evidence, correct the cause, then retry. The retry creates a new linked attempt.";
        } else if ("ROLLBACK".equals(run.mode())) {
            if (run.issueCount() > run.recordsRemoved()) {
                actions.add("ROLLBACK");
                next = "Some migration-owned records remain blocked. Resolve only the reported references, "
                        + "then queue another retention-gated rollback from the current ledger.";
            } else {
                next = "Rollback evidence is complete. The migration ownership ledger has no blocked records.";
            }
        } else {
            actions.add("RECONCILE");
            actions.add("ROLLBACK");
            next = "Review reconciliation. If it balances, continue the parallel run; otherwise correct mapping or data and re-run.";
        }

        ReconciliationReport report = null;
        if ("COMPLETED".equals(run.status()) && !"ROLLBACK".equals(run.mode())) {
            try { report = reconciler.report(runId); }
            catch (RuntimeException ignored) { /* a run can legitimately have no reconciliation yet */ }
        }
        boolean committed = "COMPLETED".equals(run.status())
                && ("IMPORT".equals(run.mode()) || "DELTA".equals(run.mode()));
        Long checkpointCount = jdbc.queryForObject("""
                select count(*) from migration.delta_checkpoint
                where tenant_id = ? and last_success_run_id = ?
                """, Long.class, TenantContext.get().tenantId(), runId);
        boolean advanced = committed && checkpointCount != null && checkpointCount > 0;
        return new RecoveryView(run, List.copyOf(actions), next, committed, advanced, report);
    }

    @Transactional(readOnly = true)
    public PageResult<Issue> issues(UUID runId, String search, String category, int page) {
        runs.run(runId); // tenant-scoped existence check
        UUID tenantId = TenantContext.get().tenantId();
        int safePage = Math.max(0, page);
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String selected = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        String where = """
                from migration.run_issue where tenant_id = ? and run_id = ?
                  and (? = '' or category = ?)
                  and (? = '' or lower(concat_ws(' ', source_object, source_record_id, source_label,
                                                  field_name, related_object, related_record_id, reason)) like ?)
                """;
        Object[] args = {tenantId, runId, selected, selected, term, "%" + term + "%"};
        Long total = jdbc.queryForObject("select count(*) " + where, Long.class, args);
        List<Issue> items = jdbc.query("""
                select severity, category, source_object, source_record_id, source_label, field_name,
                       related_object, related_record_id, related_label, reason
                """ + where + " order by created_at, id limit 100 offset ?",
                (rs, i) -> new Issue(rs.getString("severity"), rs.getString("category"),
                        rs.getString("source_object"), rs.getString("source_record_id"),
                        rs.getString("source_label"), rs.getString("field_name"),
                        rs.getString("related_object"), rs.getString("related_record_id"),
                        rs.getString("related_label"), rs.getString("reason")),
                tenantId, runId, selected, selected, term, "%" + term + "%", safePage * PAGE_SIZE);
        return PageResult.of(items, safePage, PAGE_SIZE, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public List<RecoveryActionRow> actions(UUID planId) {
        return jdbc.query("""
                select id, run_id, action, status, reason, result_run_id, detail, created_at
                from migration.recovery_action where tenant_id = ? and plan_id = ?
                order by created_at desc limit 100
                """, (rs, i) -> new RecoveryActionRow(rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class), rs.getString("action"),
                        rs.getString("status"), rs.getString("reason"),
                        rs.getObject("result_run_id", UUID.class), rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()), TenantContext.get().tenantId(), planId);
    }

    @Transactional(readOnly = true)
    public List<DeltaCheckpointRow> checkpoints(UUID planId) {
        return jdbc.query("""
                select source_object, watermark, last_success_run_id, records_created, records_updated, updated_at
                from migration.delta_checkpoint where tenant_id = ? and plan_id = ? order by source_object
                """, (rs, i) -> new DeltaCheckpointRow(rs.getString("source_object"),
                        rs.getTimestamp("watermark") == null ? null : rs.getTimestamp("watermark").toInstant(),
                        rs.getObject("last_success_run_id", UUID.class), rs.getLong("records_created"),
                        rs.getLong("records_updated"), rs.getTimestamp("updated_at").toInstant()),
                TenantContext.get().tenantId(), planId);
    }

    @Transactional
    public RunHandle reconcile(UUID planId, String reason) {
        CrmRole.requireImport(TenantContext.get().role());
        String why = reason == null ? "Operator requested reconciliation" : reason.trim();
        if (why.isBlank()) throw new IllegalArgumentException("A reconciliation reason is required");
        RunHandle queued = runs.queue(planId, "RECONCILE");
        runs.recordRecovery(planId, null, "RECONCILE", why, queued.runId(),
                "Fresh source-to-target reconciliation queued");
        return queued;
    }

    @Transactional
    public RunHandle rollback(UUID planId, String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.isBlank()) throw new IllegalArgumentException("A rollback reason is required");
        RunHandle queued = runs.queue(planId, "ROLLBACK");
        runs.recordRecovery(planId, null, "ROLLBACK", why, queued.runId(),
                "Retention-gated exact-ledger rollback queued");
        return queued;
    }
}
