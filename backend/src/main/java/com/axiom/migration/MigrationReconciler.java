package com.axiom.migration;

import com.axiom.common.NotFoundException;
import com.axiom.migration.MigrationModel.Issue;
import com.axiom.migration.MigrationModel.ObjectOutcome;
import com.axiom.migration.MigrationModel.ReconciliationLine;
import com.axiom.migration.MigrationModel.ReconciliationReport;
import com.axiom.migration.MigrationModel.PlanContext;
import com.axiom.migration.MigrationModel.ObjectPlan;
import com.axiom.migration.SourceContract.SourceAdapter;
import com.axiom.migration.SourceContract.SourceRecord;
import com.axiom.migration.SourceContract.SourceSession;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

/**
 * The reconciliation report (FR-MIG-006, F-290).
 *
 * <h2>Counts and sums, because they fail differently</h2>
 * "Counts catch missing records; sums catch value corruption — a currency
 * mis-mapped or a decimal shifted shows up as a sum mismatch even when the
 * counts tie out." So every line carries both, and {@code balanced} is only true
 * when both agree. A line that does not balance is not softened: the report is
 * the artefact a project sponsor signs, and a report that rounds its own
 * failures away is worse than none.
 *
 * <h2>Target figures come from the target</h2>
 * The target count and sum are read back out of {@code crm.*}/{@code sales.*}
 * through the record-map join, not carried forward from what the importer
 * thought it wrote. An importer that believed it wrote a row is exactly the
 * thing being checked.
 */
@Service
public class MigrationReconciler {

    private final JdbcTemplate jdbc;

    public MigrationReconciler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persist one line per source object. Called by {@link MigrationWorker} once a
     * run finishes — for a dry run the "target" figures are what WOULD be written,
     * so the same table serves the pre-flight report and the post-import report and
     * the two are directly comparable.
     */
    @Transactional
    public void persist(UUID runId, UUID planId, List<ObjectOutcome> outcomes, boolean dryRun) {
        UUID tenantId = TenantContext.get().tenantId();
        for (ObjectOutcome outcome : outcomes) {
            long targetCount;
            BigDecimal targetSum;
            if (dryRun) {
                targetCount = outcome.toCreate() + outcome.toUpdate();
                targetSum = null;
            } else {
                targetCount = targetCount(tenantId, planId, outcome.sourceObject());
                targetSum = targetSum(tenantId, planId, outcome.sourceObject(), outcome.targetEntity());
            }
            boolean balanced = outcome.toSkip() == 0
                    && (targetSum == null || outcome.sourceAmountSum() == null
                        || outcome.sourceAmountSum().compareTo(targetSum) == 0);

            jdbc.update("""
                    insert into migration.reconciliation_line
                      (tenant_id, run_id, source_object, target_entity, source_count, target_count,
                       not_migrated_count, source_amount_sum, target_amount_sum, currency_code, balanced)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, run_id, source_object) do update
                       set target_entity = excluded.target_entity,
                           source_count = excluded.source_count,
                           target_count = excluded.target_count,
                           not_migrated_count = excluded.not_migrated_count,
                           source_amount_sum = excluded.source_amount_sum,
                           target_amount_sum = excluded.target_amount_sum,
                           balanced = excluded.balanced
                    """, tenantId, runId, outcome.sourceObject(),
                    outcome.targetEntity() == null ? "(unmapped)" : outcome.targetEntity(),
                    outcome.sourceCount(), targetCount, outcome.toSkip(),
                    outcome.sourceAmountSum(), targetSum, "SOURCE", balanced);
        }
    }

    private long targetCount(UUID tenantId, UUID planId, String sourceObject) {
        Long n = jdbc.queryForObject("""
                select count(*) from migration.record_map
                where tenant_id = ? and plan_id = ? and source_object = ? and state = 'LIVE'
                  and target_entity <> 'OPPORTUNITY_CONTACT_ROLE'
                """, Long.class, tenantId, planId, sourceObject);
        return n == null ? 0 : n;
    }

    /**
     * Monetary sum read back from the target table. Only opportunities carry a
     * financial field in the current target schema; the others return null rather
     * than zero, because "no monetary field on this object" and "the money adds up
     * to nothing" are different statements.
     */
    private BigDecimal targetSum(UUID tenantId, UUID planId, String sourceObject, String targetEntity) {
        if (!TargetSchema.OPPORTUNITY.equals(targetEntity)) return null;
        return jdbc.queryForObject("""
                select coalesce(sum(o.amount), 0) from sales.opportunity o
                join migration.record_map m on m.tenant_id = o.tenant_id and m.target_id = o.id
                where m.tenant_id = ? and m.plan_id = ? and m.source_object = ?
                  and m.target_entity = 'OPPORTUNITY' and m.state = 'LIVE'
                """, BigDecimal.class, tenantId, planId, sourceObject);
    }

    /** Re-read the authoritative source and target without mutating business
     * records. This is the operator's on-demand drift check after an import or
     * any number of delta runs. */
    @Transactional
    public List<Issue> reconcileNow(UUID runId, PlanContext plan, SourceAdapter adapter, SourceSession session) {
        UUID tenantId = TenantContext.get().tenantId();
        List<Issue> issues = new ArrayList<>();
        for (ObjectPlan object : plan.objects()) {
            if (object.targetEntity() == null) continue;
            List<SourceRecord> records = adapter.records(session, object.sourceObject(), null);
            Set<String> live = new HashSet<>(jdbc.query("""
                    select source_record_id from migration.record_map
                    where tenant_id = ? and plan_id = ? and source_object = ? and state = 'LIVE'
                      and target_entity <> 'OPPORTUNITY_CONTACT_ROLE'
                    """, (rs, i) -> rs.getString(1), tenantId, plan.planId(), object.sourceObject()));
            for (SourceRecord source : records) {
                if (!live.contains(source.sourceId())) {
                    issues.add(Issue.skipped(object.sourceObject(), source.sourceId(), source.label(),
                            "No live Axiom target is recorded for this source record. Review the original run "
                            + "issues, correct validation or mapping, then run a delta re-sync."));
                }
            }
            BigDecimal sourceSum = BigDecimal.ZERO;
            for (SourceRecord source : records) {
                for (String field : object.moneyFields()) {
                    sourceSum = sourceSum.add(MigrationAssembly.money(source.values().get(field)));
                }
            }
            long targetCount = targetCount(tenantId, plan.planId(), object.sourceObject());
            BigDecimal targetSum = targetSum(tenantId, plan.planId(), object.sourceObject(), object.targetEntity());
            boolean sumsBalance = targetSum == null || sourceSum.compareTo(targetSum) == 0;
            boolean balanced = records.size() == targetCount && sumsBalance;
            jdbc.update("""
                    insert into migration.reconciliation_line
                      (tenant_id, run_id, source_object, target_entity, source_count, target_count,
                       not_migrated_count, source_amount_sum, target_amount_sum, currency_code, balanced)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SOURCE', ?)
                    on conflict (tenant_id, run_id, source_object) do update
                      set source_count = excluded.source_count, target_count = excluded.target_count,
                          not_migrated_count = excluded.not_migrated_count,
                          source_amount_sum = excluded.source_amount_sum,
                          target_amount_sum = excluded.target_amount_sum, balanced = excluded.balanced
                    """, tenantId, runId, object.sourceObject(), object.targetEntity(), records.size(),
                    targetCount, Math.max(0, records.size() - live.size()), sourceSum, targetSum, balanced);
        }
        return List.copyOf(issues);
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public ReconciliationReport report(UUID runId) {
        UUID tenantId = TenantContext.get().tenantId();
        List<Object[]> run = jdbc.query("""
                select r.plan_id, p.name from migration.run r
                join migration.plan p on p.tenant_id = r.tenant_id and p.id = r.plan_id
                where r.tenant_id = ? and r.id = ?
                """, (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getString(2)}, tenantId, runId);
        if (run.isEmpty()) throw new NotFoundException("No migration run " + runId);

        List<ReconciliationLine> lines = jdbc.query("""
                select source_object, target_entity, source_count, target_count, not_migrated_count,
                       source_amount_sum, target_amount_sum, currency_code, balanced
                from migration.reconciliation_line
                where tenant_id = ? and run_id = ?
                order by source_object
                """, (rs, i) -> new ReconciliationLine(rs.getString("source_object"),
                        rs.getString("target_entity"), rs.getLong("source_count"), rs.getLong("target_count"),
                        rs.getLong("not_migrated_count"), rs.getBigDecimal("source_amount_sum"),
                        rs.getBigDecimal("target_amount_sum"), rs.getString("currency_code"),
                        rs.getBoolean("balanced")),
                tenantId, runId);

        // Every record not migrated, with the reason — the third column of
        // FR-MIG-006 and the one an ordinary importer never produces.
        List<Issue> notMigrated = new ArrayList<>(jdbc.query("""
                select severity, category, source_object, source_record_id, source_label, field_name,
                       related_object, related_record_id, related_label, reason
                from migration.run_issue
                where tenant_id = ? and run_id = ?
                  and category in ('VALIDATION','DUPLICATE','REFERENTIAL_GAP','SKIPPED')
                order by source_object, source_record_id, category
                """, (rs, i) -> new Issue(rs.getString("severity"), rs.getString("category"),
                        rs.getString("source_object"), rs.getString("source_record_id"),
                        rs.getString("source_label"), rs.getString("field_name"),
                        rs.getString("related_object"), rs.getString("related_record_id"),
                        rs.getString("related_label"), rs.getString("reason")),
                tenantId, runId));

        boolean balanced = !lines.isEmpty() && lines.stream().allMatch(ReconciliationLine::balanced);
        return new ReconciliationReport(runId, (UUID) run.get(0)[0], (String) run.get(0)[1],
                Instant.now(), lines, notMigrated, balanced);
    }
}
