package com.axiom.migration;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ObjectOutcome;
import com.axiom.migration.MigrationModel.PreFlightReport;
import com.axiom.migration.MigrationModel.RunHandle;
import com.axiom.migration.MigrationPlanService.MappingReview;
import com.axiom.migration.MigrationPlanService.PlanRow;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Queues runs and reads their reports. Nothing here does any migration work —
 * that is {@link MigrationWorker}'s job, on the worker tier.
 *
 * <h2>The job handle</h2>
 * Every long operation returns a {@link RunHandle} the instant the QUEUED row is
 * written. The caller polls the same handle for status and progress. No HTTP
 * request is ever held open across an import, a dry run or a rollback
 * (system-design §3.3).
 *
 * <h2>Where the gates are</h2>
 * The role check and the unmapped-field acknowledgement are enforced <em>here</em>,
 * at queue time, because this is the last point at which there is a request
 * principal to hold responsible. By the time the worker picks the run up the
 * operator is gone and "who authorised this" would be unanswerable.
 */
@Service
public class MigrationRunService {

    private final JdbcTemplate jdbc;
    private final MigrationPlanService plans;
    private final MigrationRollbackService rollback;
    private final AuditService audit;

    public MigrationRunService(JdbcTemplate jdbc, MigrationPlanService plans,
                               MigrationRollbackService rollback, AuditService audit) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.rollback = rollback;
        this.audit = audit;
    }

    private static final Set<String> MODES = Set.of("DRY_RUN", "IMPORT", "DELTA", "ROLLBACK");

    // ------------------------------------------------------------------ queue

    @Transactional
    public RunHandle queue(UUID planId, String mode) {
        TenantContext.Principal principal = TenantContext.get();
        String normalised = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        if (!MODES.contains(normalised)) {
            throw new IllegalArgumentException("Migration run mode must be one of " + MODES);
        }
        CrmRole.requireImport(principal.role());
        PlanRow plan = plans.plan(planId);

        Instant deltaSince = null;
        switch (normalised) {
            case "IMPORT" -> requireAcknowledgedUnmappedFields(planId);
            case "DELTA" -> {
                requireAcknowledgedUnmappedFields(planId);
                if (plan.importedAt() == null) {
                    throw new ConflictException("Plan \"" + plan.name() + "\" has not been imported yet. "
                            + "A delta re-sync only makes sense after an initial import.");
                }
                // The high-water mark of source modification times seen by the
                // previous run. Records at or before it are already in Axiom.
                deltaSince = plan.deltaWatermark();
            }
            case "ROLLBACK" -> {
                MigrationRollbackService.requireRollbackRole();
                rollback.requireWithinRetention(rollback.plan(principal.tenantId(), planId));
            }
            default -> { /* DRY_RUN is deliberately ungated: iterate freely */ }
        }

        if (jdbc.queryForObject("""
                select count(*) from migration.run
                where tenant_id = ? and plan_id = ? and status in ('QUEUED','RUNNING')
                """, Long.class, principal.tenantId(), planId) > 0) {
            throw new ConflictException("Plan \"" + plan.name() + "\" already has a run in flight. "
                    + "Wait for it to finish before queueing another.");
        }

        UUID runId = jdbc.queryForObject("""
                insert into migration.run (tenant_id, plan_id, mode, status, delta_since, requested_by)
                values (?, ?, ?, 'QUEUED', ?, ?)
                returning id
                """, UUID.class, principal.tenantId(), planId, normalised,
                MigrationImporter.timestamp(deltaSince), principal.userId());

        audit.record("MIGRATION_RUN_QUEUED", "MIGRATION_PLAN", planId,
                normalised + " queued for migration plan \"" + plan.name() + "\"",
                Map.of("runId", runId.toString(), "mode", normalised));

        return run(runId);
    }

    private void requireAcknowledgedUnmappedFields(UUID planId) {
        MappingReview review = plans.review(planId);
        if (!review.acknowledgementCurrent()) {
            throw new ConflictException("This plan cannot be imported until the list of source fields that "
                    + "will NOT be migrated has been acknowledged. " + review.acknowledgementStatement());
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public RunHandle run(UUID runId) {
        List<RunHandle> rows = jdbc.query(RUN_SELECT + " where tenant_id = ? and id = ?",
                RUN_MAPPER, TenantContext.get().tenantId(), runId);
        if (rows.isEmpty()) throw new NotFoundException("No migration run " + runId);
        return rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<RunHandle> runs(UUID planId) {
        return jdbc.query(RUN_SELECT + " where tenant_id = ? and plan_id = ? order by queued_at desc",
                RUN_MAPPER, TenantContext.get().tenantId(), planId);
    }

    /**
     * The FR-MIG-003 pre-flight report as stored. Reconstructed from the run's
     * own artefacts so a report read a week later is the report that was
     * generated, not a re-run against a source that has since moved.
     */
    @Transactional(readOnly = true)
    public PreFlightReport report(UUID runId) {
        UUID tenantId = TenantContext.get().tenantId();
        RunHandle handle = run(runId);
        PlanRow plan = plans.plan(handle.planId());

        List<ObjectOutcome> objects = jdbc.query("""
                select source_object, target_entity, source_count, target_count, not_migrated_count,
                       source_amount_sum
                from migration.reconciliation_line
                where tenant_id = ? and run_id = ? order by source_object
                """, (rs, i) -> new ObjectOutcome(rs.getString("source_object"), rs.getString("target_entity"),
                        rs.getLong("source_count"), rs.getLong("target_count"), 0L,
                        rs.getLong("not_migrated_count"),
                        rs.getBigDecimal("source_amount_sum") == null
                                ? BigDecimal.ZERO : rs.getBigDecimal("source_amount_sum"),
                        List.of()),
                tenantId, runId);

        List<Issue> issues = jdbc.query("""
                select severity, category, source_object, source_record_id, source_label, field_name,
                       related_object, related_record_id, related_label, reason
                from migration.run_issue where tenant_id = ? and run_id = ?
                order by case category when 'VALIDATION' then 1 when 'REFERENTIAL_GAP' then 2
                                       when 'DUPLICATE' then 3 when 'UNMAPPED_FIELD' then 4 else 5 end,
                         source_object, source_record_id
                """, ISSUE_MAPPER, tenantId, runId);

        List<String> unmapped = issues.stream()
                .filter(i -> "UNMAPPED_FIELD".equals(i.category()))
                .map(i -> (i.sourceObject() == null ? "" : i.sourceObject() + ".") + i.fieldName())
                .toList();

        return new PreFlightReport(plan.id(), plan.name(),
                handle.finishedAt() == null ? handle.queuedAt() : handle.finishedAt(),
                null, objects, issues, unmapped,
                handle.recordsCreated(), handle.recordsUpdated(), handle.recordsSkipped(),
                issues.stream().filter(i -> "VALIDATION".equals(i.category())).count(),
                issues.stream().filter(i -> "DUPLICATE".equals(i.category())).count(),
                issues.stream().filter(i -> "REFERENTIAL_GAP".equals(i.category())).count());
    }

    private static final String RUN_SELECT = """
            select id, plan_id, mode, status, phase, total_units, processed_units, records_created,
                   records_updated, records_skipped, records_removed, issue_count,
                   queued_at, started_at, finished_at, message
            from migration.run
            """;

    private static final RowMapper<RunHandle> RUN_MAPPER = (rs, i) -> {
        long total = rs.getLong("total_units");
        long processed = rs.getLong("processed_units");
        int percent = "COMPLETED".equals(rs.getString("status")) ? 100
                : total <= 0 ? 0 : (int) Math.min(99, (processed * 100) / total);
        return new RunHandle(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getString("mode"), rs.getString("status"), rs.getString("phase"), total, processed, percent,
                rs.getLong("records_created"), rs.getLong("records_updated"), rs.getLong("records_skipped"),
                rs.getLong("records_removed"), rs.getLong("issue_count"),
                rs.getTimestamp("queued_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                rs.getString("message"));
    };

    private static final RowMapper<Issue> ISSUE_MAPPER = (rs, i) -> new Issue(
            rs.getString("severity"), rs.getString("category"), rs.getString("source_object"),
            rs.getString("source_record_id"), rs.getString("source_label"), rs.getString("field_name"),
            rs.getString("related_object"), rs.getString("related_record_id"), rs.getString("related_label"),
            rs.getString("reason"));
}
