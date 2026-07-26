package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconciliation: projected aggregates against authoritative recomputation, on a
 * schedule.
 *
 * <h2>Why this class is not optional</h2>
 * ADR-008 names the cost of this whole pattern in one sentence: <i>"Projection
 * logic is a second implementation of business meaning, and it can drift from the
 * transactional model. This is the real long-term risk of this pattern. It
 * requires reconciliation tests that compare projected aggregates against
 * authoritative recomputation on a schedule — not as a one-time verification."</i>
 *
 * <p>Its Compliance section repeats it as a hard requirement. So drift detection
 * here is a scheduled job writing durable observations, not a test that passed
 * once in CI. A projection bug that a unit test cannot see — a stage renamed, a
 * soft delete that never pruned, a rollup that double-counts after a merge — shows
 * up as a row in {@code analytics.reconciliation_run} the next morning.
 *
 * <h2>The checks are deliberately dumb</h2>
 * Every check recomputes the authoritative side with the simplest possible SQL
 * over the OLTP tables — {@code count(*)}, {@code sum(amount)} — rather than
 * reusing any of the projection's own SQL. Sharing code between the two sides
 * would make the comparison agree with itself: a reconciliation that imports the
 * logic it is checking cannot detect the bug in that logic. Simplicity is the
 * point; these queries are slow and infrequent by design.
 *
 * <h2>Tolerance is zero, and stays zero</h2>
 * These are counts and exact decimal sums of the same rows. There is no rounding
 * to accommodate, so a non-zero drift is always a real defect. A tolerance column
 * exists for future checks over derived values, but every check registered today
 * uses zero, because a tolerance chosen to make a red check go green is how drift
 * becomes permanent.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record CheckResult(String checkCode, String checkLabel, String dataset,
                              BigDecimal projected, BigDecimal authoritative, BigDecimal drift,
                              BigDecimal driftPct, String status, String detail, long durationMs) {}

    public record ReconciliationReport(Instant runAt, int checksRun, int checksMatched, int checksDrifted,
                                       BigDecimal totalAbsoluteDrift, List<CheckResult> checks,
                                       String verdict) {}

    /**
     * One check: a projected aggregate and the authoritative recomputation of the
     * same quantity. Both queries take exactly one parameter, the tenant id.
     */
    private record Check(String code, String label, AnalyticsDataset dataset,
                         String projectedSql, String authoritativeSql) {}

    private static final List<Check> CHECKS = List.of(
            new Check("OPPORTUNITY_ROW_COUNT", "Opportunity rows projected", AnalyticsDataset.OPPORTUNITY,
                    "select count(*) from analytics.opportunity_fact where tenant_id = ?",
                    "select count(*) from sales.opportunity where tenant_id = ?"),
            new Check("OPPORTUNITY_TOTAL_AMOUNT", "Total opportunity value", AnalyticsDataset.OPPORTUNITY,
                    "select coalesce(sum(amount), 0) from analytics.opportunity_fact where tenant_id = ?",
                    "select coalesce(sum(amount), 0) from sales.opportunity where tenant_id = ?"),
            new Check("OPEN_PIPELINE_AMOUNT", "Open pipeline value", AnalyticsDataset.OPPORTUNITY,
                    "select coalesce(sum(amount), 0) from analytics.opportunity_fact"
                            + " where tenant_id = ? and is_closed = false",
                    "select coalesce(sum(amount), 0) from sales.opportunity"
                            + " where tenant_id = ? and is_closed = false"),
            new Check("CLOSED_WON_AMOUNT", "Closed-won value", AnalyticsDataset.OPPORTUNITY,
                    "select coalesce(sum(amount), 0) from analytics.opportunity_fact"
                            + " where tenant_id = ? and is_won = true",
                    "select coalesce(sum(amount), 0) from sales.opportunity"
                            + " where tenant_id = ? and is_won = true"),
            new Check("LEAD_ROW_COUNT", "Lead rows projected", AnalyticsDataset.LEAD,
                    "select count(*) from analytics.lead_fact where tenant_id = ?",
                    "select count(*) from crm.lead where tenant_id = ? and deleted_at is null"),
            new Check("ACCOUNT_ROW_COUNT", "Account rows projected", AnalyticsDataset.ACCOUNT,
                    "select count(*) from analytics.account_fact where tenant_id = ?",
                    "select count(*) from crm.account where tenant_id = ? and deleted_at is null"),
            new Check("ACTIVITY_ROW_COUNT", "Activity rows projected", AnalyticsDataset.ACTIVITY,
                    "select count(*) from analytics.activity_fact where tenant_id = ?",
                    "select count(*) from engagement.activity where tenant_id = ? and deleted_at is null"),
            new Check("STAGE_TRANSITION_COUNT", "Stage transitions projected", AnalyticsDataset.OPPORTUNITY,
                    "select count(*) from analytics.stage_transition_fact where tenant_id = ?",
                    "select count(*) from sales.stage_history where tenant_id = ?"),
            // The rollup check. account_fact.open_pipeline_amount is the ONE place the
            // read model is derived from itself, so it is also the one place a drift
            // could compound silently. Checked against the OLTP tables directly.
            new Check("ACCOUNT_ROLLUP_PIPELINE", "Account open-pipeline rollup", AnalyticsDataset.ACCOUNT,
                    "select coalesce(sum(open_pipeline_amount), 0) from analytics.account_fact"
                            + " where tenant_id = ?",
                    "select coalesce(sum(o.amount), 0) from sales.opportunity o"
                            + " join crm.account a on a.tenant_id = o.tenant_id and a.id = o.account_id"
                            + " and a.deleted_at is null"
                            + " where o.tenant_id = ? and o.is_closed = false"));

    private final JdbcTemplate jdbc;
    private final SystemTaskRunner tasks;
    private final AuditService audit;
    private final boolean enabled;

    /** Annotated: this bean also has a package-private test constructor. */
    @Autowired
    public ReconciliationService(JdbcTemplate jdbc, SystemTaskRunner tasks, AuditService audit,
                                 @Value("${axiom.analytics.reconciliation-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.audit = audit;
        this.enabled = enabled;
    }

    ReconciliationService(JdbcTemplate jdbc, SystemTaskRunner tasks, AuditService audit) {
        this(jdbc, tasks, audit, true);
    }

    /** Nightly, after the snapshot capture and before anyone reads yesterday's numbers. */
    @Scheduled(cron = "${axiom.analytics.reconciliation-cron:0 20 3 * * *}")
    public void scheduledReconciliation() {
        if (!enabled) return;
        tasks.forEachTenant("analytics-reconciliation", tenantId -> {
            ReconciliationReport report = reconcile(tenantId);
            if (report.checksDrifted() > 0) {
                // Logged at ERROR because ADR-008 gives projection drift "its own
                // incident class". A drift that only appears in a table nobody reads
                // is a control that exists on paper.
                log.error("Projection drift detected for tenant {}: {} of {} checks drifted, "
                                + "total absolute drift {}", tenantId, report.checksDrifted(),
                        report.checksRun(), report.totalAbsoluteDrift());
            }
            return report.checksDrifted();
        });
    }

    /** On-demand run for the current tenant. */
    @Transactional
    public ReconciliationReport reconcileCurrentTenant() {
        UUID tenantId = TenantContext.get().tenantId();
        ReconciliationReport report = reconcile(tenantId);
        audit.record("ANALYTICS_RECONCILIATION", "PROJECTION", tenantId,
                report.verdict(),
                Map.of("checksRun", report.checksRun(), "checksDrifted", report.checksDrifted(),
                        "totalAbsoluteDrift", report.totalAbsoluteDrift().toPlainString()));
        return report;
    }

    @Transactional
    public ReconciliationReport reconcile(UUID tenantId) {
        Instant runAt = Instant.now();
        List<CheckResult> results = new ArrayList<>();
        BigDecimal totalDrift = BigDecimal.ZERO;
        int matched = 0;

        for (Check check : CHECKS) {
            long started = System.nanoTime();
            CheckResult result;
            try {
                BigDecimal projected = value(check.projectedSql(), tenantId);
                BigDecimal authoritative = value(check.authoritativeSql(), tenantId);
                BigDecimal drift = projected.subtract(authoritative);
                BigDecimal driftPct = authoritative.signum() == 0
                        ? (drift.signum() == 0 ? BigDecimal.ZERO : null)
                        : drift.divide(authoritative, 6, RoundingMode.HALF_UP);
                long duration = Math.round((System.nanoTime() - started) / 1_000_000.0);
                boolean match = drift.compareTo(BigDecimal.ZERO) == 0;
                if (match) matched++;
                totalDrift = totalDrift.add(drift.abs());
                result = new CheckResult(check.code(), check.label(), check.dataset().name(),
                        projected, authoritative, drift, driftPct, match ? "MATCH" : "DRIFT",
                        match ? "Projected aggregate equals authoritative recomputation."
                              : "Projected aggregate differs from authoritative recomputation by "
                                + drift.toPlainString() + ". Run a backfill for "
                                + check.dataset().name() + " and re-run this check; if the drift"
                                + " survives a rebuild the projection SQL is wrong, not stale.",
                        duration);
            } catch (RuntimeException ex) {
                long duration = Math.round((System.nanoTime() - started) / 1_000_000.0);
                result = new CheckResult(check.code(), check.label(), check.dataset().name(),
                        null, null, null, null, "ERROR", ex.getMessage(), duration);
            }
            results.add(result);
            record(tenantId, result);
        }

        int drifted = results.size() - matched;
        String verdict = drifted == 0
                ? "All " + results.size() + " reconciliation checks matched: zero drift between the"
                  + " read model and authoritative recomputation."
                : drifted + " of " + results.size() + " checks drifted; total absolute drift "
                  + totalDrift.toPlainString() + ".";
        return new ReconciliationReport(runAt, results.size(), matched, drifted, totalDrift,
                results, verdict);
    }

    @Transactional(readOnly = true)
    public List<CheckResult> recentRuns(int limit) {
        return jdbc.query("""
                select check_code, check_label, dataset, projected, authoritative, drift, drift_pct,
                       status, detail, duration_ms
                  from analytics.reconciliation_run
                 where tenant_id = ?
                 order by run_at desc, check_code
                 limit ?
                """, (rs, i) -> new CheckResult(rs.getString("check_code"), rs.getString("check_label"),
                rs.getString("dataset"), rs.getBigDecimal("projected"), rs.getBigDecimal("authoritative"),
                rs.getBigDecimal("drift"), rs.getBigDecimal("drift_pct"), rs.getString("status"),
                rs.getString("detail"), rs.getInt("duration_ms")),
                TenantContext.get().tenantId(), Math.min(Math.max(limit, 1), 200));
    }

    private BigDecimal value(String sql, UUID tenantId) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, tenantId);
        return value == null ? BigDecimal.ZERO : value;
    }

    private void record(UUID tenantId, CheckResult result) {
        jdbc.update("""
                insert into analytics.reconciliation_run
                  (tenant_id, check_code, check_label, dataset, projected, authoritative, drift,
                   drift_pct, tolerance, status, detail, duration_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                """, tenantId, result.checkCode(), result.checkLabel(), result.dataset(),
                result.projected(), result.authoritative(), result.drift(), result.driftPct(),
                result.status(), result.detail(), (int) result.durationMs());
    }
}
