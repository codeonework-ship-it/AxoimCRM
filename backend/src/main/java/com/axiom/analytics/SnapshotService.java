package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled, immutable snapshots (FR-RPT-008, FR-OPP-015, FR-FCT-004 … FR-FCT-006,
 * ADR-008 decision 2).
 *
 * <h2>Why snapshots rather than reconstruction</h2>
 * Doc 14 §7: reconstructing a historical forecast from an audit log "is slow,
 * fragile and produces numbers that do not quite tie out — which in a forecast
 * review is worse than having no number at all." A snapshot is what makes pipeline
 * as of two dates and the forecast movement waterfall <em>exact</em> instead of
 * approximately right.
 *
 * <h2>Immutable, enforced below this class</h2>
 * Nothing here can update a snapshot row even by mistake: the tables carry a
 * trigger that refuses UPDATE outright, and {@code axiom_app} holds no UPDATE
 * grant on them. This service only ever inserts. Re-running a capture for a day
 * that already has one is refused by a unique constraint rather than silently
 * doubling the history — an accidental second run is a much more likely event than
 * a deliberate one.
 *
 * <h2>Retention, because ADR-008 says so plainly</h2>
 * "Snapshots need a retention policy or they become the largest data in the
 * system." The sweep is the single caller permitted to delete, and it announces
 * itself to the immutability trigger by setting {@code app.snapshot_retention_sweep}
 * for the duration of its transaction. Any other DELETE — a bug, a script, a
 * well-meaning cleanup — fails.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    public record PipelineSnapshotRow(UUID id, LocalDate capturedOn, Instant capturedAt, String captureReason,
                                      String stageName, int stageSortOrder, String forecastCategory,
                                      int opportunityCount, BigDecimal totalAmount,
                                      BigDecimal weightedAmount) {}

    public record ForecastSnapshotRow(UUID id, LocalDate capturedOn, Instant capturedAt, String captureReason,
                                      String periodCode, LocalDate periodStart, LocalDate periodEnd,
                                      BigDecimal commitAmount, BigDecimal bestCaseAmount,
                                      BigDecimal pipelineAmount, BigDecimal omittedAmount,
                                      BigDecimal closedWonAmount, BigDecimal closedLostAmount,
                                      int openCount, int lineCount) {}

    public record CaptureResult(LocalDate capturedOn, String reason, int pipelineRows,
                                int forecastSnapshots, int forecastLines, String note) {}

    /** One component of the movement waterfall. The components must sum to the net change. */
    public record WaterfallComponent(String component, BigDecimal amount, int count, String explanation) {}

    public record Waterfall(String periodCode, LocalDate fromDate, LocalDate toDate,
                            BigDecimal openingAmount, BigDecimal closingAmount, BigDecimal netChange,
                            List<WaterfallComponent> components, BigDecimal residual,
                            boolean reconciles, String note) {}

    private final JdbcTemplate jdbc;
    private final SystemTaskRunner tasks;
    private final AuditService audit;
    private final boolean enabled;

    /** Annotated: this bean also has a package-private test constructor. */
    @Autowired
    public SnapshotService(JdbcTemplate jdbc, SystemTaskRunner tasks, AuditService audit,
                           @Value("${axiom.analytics.snapshots-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.audit = audit;
        this.enabled = enabled;
    }

    SnapshotService(JdbcTemplate jdbc, SystemTaskRunner tasks, AuditService audit) {
        this(jdbc, tasks, audit, true);
    }

    // ------------------------------------------------------------------ capture

    /** Daily at 01:10 local time — after the day's activity, before anyone reads the trend. */
    @Scheduled(cron = "${axiom.analytics.snapshot-cron:0 10 1 * * *}")
    public void scheduledCapture() {
        if (!enabled) return;
        tasks.forEachTenant("analytics-snapshot", tenantId -> {
            try {
                CaptureResult result = capture(tenantId, "SCHEDULED");
                return result.pipelineRows() + result.forecastSnapshots();
            } catch (ConflictException already) {
                // Today is already captured. Not an error: it is the constraint doing
                // its job after a restart or a manual run earlier in the day.
                log.debug("Snapshot for tenant {} already captured today", tenantId);
                return 0;
            }
        });
    }

    /** Manual capture, for a period close or an ad-hoc "as of today" baseline. */
    @Transactional
    public CaptureResult captureNow(String reason) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        String captureReason = reason == null || reason.isBlank() ? "MANUAL"
                : reason.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        CaptureResult result = capture(tenantId, captureReason);
        audit.record("ANALYTICS_SNAPSHOT_CAPTURED", "SNAPSHOT", tenantId,
                "Captured " + result.pipelineRows() + " pipeline row(s) and "
                        + result.forecastSnapshots() + " forecast snapshot(s)",
                Map.of("reason", captureReason, "capturedOn", result.capturedOn().toString(),
                        "forecastLines", result.forecastLines()));
        return result;
    }

    @Transactional
    public CaptureResult capture(UUID tenantId, String reason) {
        try {
            int pipelineRows = jdbc.update("""
                    insert into analytics.pipeline_snapshot
                      (tenant_id, capture_reason, stage_id, stage_name, stage_sort_order,
                       forecast_category, opportunity_count, total_amount, weighted_amount)
                    select f.tenant_id, ?, f.stage_id, coalesce(f.stage_name, '(no stage)'),
                           coalesce(f.stage_sort_order, 0), f.forecast_category,
                           count(*), coalesce(sum(f.amount), 0), coalesce(sum(f.weighted_amount), 0)
                      from analytics.opportunity_fact f
                     where f.tenant_id = ? and f.is_closed = false
                     group by f.tenant_id, f.stage_id, f.stage_name, f.stage_sort_order, f.forecast_category
                    """, reason, tenantId);

            List<Map<String, Object>> periods = jdbc.queryForList("""
                    select code, period_start, period_end from forecasting.forecast_period
                     where tenant_id = ? order by period_start
                    """, tenantId);

            int snapshots = 0;
            int lines = 0;
            for (Map<String, Object> period : periods) {
                String code = (String) period.get("code");
                LocalDate start = ((java.sql.Date) period.get("period_start")).toLocalDate();
                LocalDate end = ((java.sql.Date) period.get("period_end")).toLocalDate();

                UUID snapshotId = jdbc.queryForObject("""
                        insert into analytics.forecast_snapshot
                          (tenant_id, capture_reason, period_code, period_start, period_end,
                           commit_amount, best_case_amount, pipeline_amount, omitted_amount,
                           closed_won_amount, closed_lost_amount, open_count, line_count)
                        select ?, ?, ?, ?, ?,
                               coalesce(sum(f.amount) filter (where f.forecast_category = 'COMMIT'
                                                                and f.is_closed = false), 0),
                               coalesce(sum(f.amount) filter (where f.forecast_category = 'BEST_CASE'
                                                                and f.is_closed = false), 0),
                               coalesce(sum(f.amount) filter (where f.forecast_category = 'PIPELINE'
                                                                and f.is_closed = false), 0),
                               coalesce(sum(f.amount) filter (where f.forecast_category = 'OMITTED'
                                                                and f.is_closed = false), 0),
                               coalesce(sum(f.amount) filter (where f.is_won = true), 0),
                               coalesce(sum(f.amount) filter (where f.is_closed = true
                                                                and f.is_won = false), 0),
                               count(*) filter (where f.is_closed = false),
                               count(*)
                          from analytics.opportunity_fact f
                         where f.tenant_id = ? and f.close_date between ? and ?
                        returning id
                        """, UUID.class, tenantId, reason, code, start, end, tenantId, start, end);
                snapshots++;

                // The LINES are what make FR-FCT-005 and FR-FCT-006 exact: a forecast
                // that decomposes to its source deals, and a waterfall whose components
                // reconcile to the net change rather than approximately explaining it.
                lines += jdbc.update("""
                        insert into analytics.forecast_snapshot_line
                          (tenant_id, snapshot_id, opportunity_id, opportunity_name, account_name,
                           owner_id, owner_name, stage_name, forecast_category, amount,
                           weighted_amount, probability, close_date, is_closed, is_won)
                        select f.tenant_id, ?, f.opportunity_id, f.name, f.account_name,
                               f.owner_id, f.owner_name, f.stage_name, f.forecast_category, f.amount,
                               f.weighted_amount, f.probability, f.close_date, f.is_closed, f.is_won
                          from analytics.opportunity_fact f
                         where f.tenant_id = ? and f.close_date between ? and ?
                        """, snapshotId, tenantId, start, end);
            }

            return new CaptureResult(LocalDate.now(), reason, pipelineRows, snapshots, lines,
                    "Snapshot rows are immutable: this capture can never be edited, only superseded"
                            + " by the next one or removed by the retention sweep.");
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A '" + reason + "' snapshot already exists for today. "
                    + "Snapshots are immutable (ADR-008) and a day is captured once — a second run "
                    + "would double the history rather than correct it. Use a different capture reason "
                    + "if you need an additional baseline today.");
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PipelineSnapshotRow> pipelineTrend(LocalDate from, LocalDate to, Integer limit) {
        LocalDate start = from == null ? LocalDate.now().minusDays(90) : from;
        LocalDate end = to == null ? LocalDate.now() : to;
        return jdbc.query("""
                select id, captured_on, captured_at, capture_reason, stage_name, stage_sort_order,
                       forecast_category, opportunity_count, total_amount, weighted_amount
                  from analytics.pipeline_snapshot
                 where tenant_id = ? and captured_on between ? and ?
                 order by captured_on desc, stage_sort_order
                 limit ?
                """, (rs, i) -> new PipelineSnapshotRow(
                rs.getObject("id", UUID.class), rs.getDate("captured_on").toLocalDate(),
                rs.getTimestamp("captured_at").toInstant(), rs.getString("capture_reason"),
                rs.getString("stage_name"), rs.getInt("stage_sort_order"),
                rs.getString("forecast_category"), rs.getInt("opportunity_count"),
                rs.getBigDecimal("total_amount"), rs.getBigDecimal("weighted_amount")),
                TenantContext.get().tenantId(), start, end,
                limit == null ? 500 : Math.min(Math.max(limit, 1), 2000));
    }

    @Transactional(readOnly = true)
    public List<ForecastSnapshotRow> forecastSnapshots(String periodCode, Integer limit) {
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        String filter = "";
        if (periodCode != null && !periodCode.isBlank()) {
            filter = " and period_code = ?";
            args.add(periodCode.trim());
        }
        args.add(limit == null ? 60 : Math.min(Math.max(limit, 1), 500));
        return jdbc.query("""
                select id, captured_on, captured_at, capture_reason, period_code, period_start,
                       period_end, commit_amount, best_case_amount, pipeline_amount, omitted_amount,
                       closed_won_amount, closed_lost_amount, open_count, line_count
                  from analytics.forecast_snapshot
                 where tenant_id = ?""" + filter + """
                 order by captured_on desc, period_code
                 limit ?
                """, (rs, i) -> new ForecastSnapshotRow(
                rs.getObject("id", UUID.class), rs.getDate("captured_on").toLocalDate(),
                rs.getTimestamp("captured_at").toInstant(), rs.getString("capture_reason"),
                rs.getString("period_code"), rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(), rs.getBigDecimal("commit_amount"),
                rs.getBigDecimal("best_case_amount"), rs.getBigDecimal("pipeline_amount"),
                rs.getBigDecimal("omitted_amount"), rs.getBigDecimal("closed_won_amount"),
                rs.getBigDecimal("closed_lost_amount"), rs.getInt("open_count"),
                rs.getInt("line_count")), args.toArray());
    }

    /**
     * The forecast movement waterfall between two snapshots (FR-FCT-006).
     *
     * <p>The requirement is unusually strict: the components must "reconcile
     * exactly to the net change, with any residual shown explicitly rather than
     * absorbed". So this method computes each component from the snapshot LINES,
     * sums them, and returns the residual as its own field with a boolean saying
     * whether it is zero. A waterfall that quietly folds its rounding error into
     * the last bar is the thing this design exists to make impossible.
     */
    @Transactional(readOnly = true)
    public Waterfall waterfall(UUID fromSnapshotId, UUID toSnapshotId) {
        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> from = snapshotHeader(tenantId, fromSnapshotId);
        Map<String, Object> to = snapshotHeader(tenantId, toSnapshotId);

        List<Map<String, Object>> movements = jdbc.queryForList("""
                select coalesce(a.opportunity_id, b.opportunity_id) as opportunity_id,
                       a.amount as opening_amount, b.amount as closing_amount,
                       a.is_closed as opening_closed, b.is_closed as closing_closed,
                       b.is_won as closing_won, a.close_date as opening_close_date,
                       b.close_date as closing_close_date
                  from (select * from analytics.forecast_snapshot_line
                         where tenant_id = ? and snapshot_id = ?) a
                  full outer join (select * from analytics.forecast_snapshot_line
                                    where tenant_id = ? and snapshot_id = ?) b
                    on a.opportunity_id = b.opportunity_id
                """, tenantId, fromSnapshotId, tenantId, toSnapshotId);

        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : List.of("NEW", "WON", "LOST", "INCREASED", "DECREASED", "SLIPPED", "PULLED_IN")) {
            amounts.put(key, BigDecimal.ZERO);
            counts.put(key, 0);
        }

        for (Map<String, Object> row : movements) {
            BigDecimal opening = (BigDecimal) row.get("opening_amount");
            BigDecimal closing = (BigDecimal) row.get("closing_amount");
            Boolean closingWon = (Boolean) row.get("closing_won");
            Boolean closingClosed = (Boolean) row.get("closing_closed");
            Boolean openingClosed = (Boolean) row.get("opening_closed");

            if (opening == null && closing != null) {
                add(amounts, counts, "NEW", closing);
            } else if (opening != null && closing == null) {
                add(amounts, counts, "SLIPPED", opening.negate());
            } else if (opening != null) {
                if (Boolean.FALSE.equals(openingClosed) && Boolean.TRUE.equals(closingClosed)) {
                    add(amounts, counts, Boolean.TRUE.equals(closingWon) ? "WON" : "LOST",
                            closing.subtract(opening));
                }
                int comparison = closing.compareTo(opening);
                if (comparison > 0) add(amounts, counts, "INCREASED", closing.subtract(opening));
                else if (comparison < 0) add(amounts, counts, "DECREASED", closing.subtract(opening));
                java.sql.Date openingDate = (java.sql.Date) row.get("opening_close_date");
                java.sql.Date closingDate = (java.sql.Date) row.get("closing_close_date");
                if (openingDate != null && closingDate != null && closingDate.after(openingDate)) {
                    counts.merge("SLIPPED", 1, Integer::sum);
                } else if (openingDate != null && closingDate != null && closingDate.before(openingDate)) {
                    counts.merge("PULLED_IN", 1, Integer::sum);
                }
            }
        }

        BigDecimal opening = total(from);
        BigDecimal closing = total(to);
        BigDecimal netChange = closing.subtract(opening);
        BigDecimal componentSum = amounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal residual = netChange.subtract(componentSum);

        List<WaterfallComponent> components = new ArrayList<>();
        components.add(component(amounts, counts, "NEW", "Opportunities present at the close but not at the open"));
        components.add(component(amounts, counts, "WON", "Closed won between the two snapshots"));
        components.add(component(amounts, counts, "LOST", "Closed lost between the two snapshots"));
        components.add(component(amounts, counts, "INCREASED", "Amount raised on a deal present in both"));
        components.add(component(amounts, counts, "DECREASED", "Amount lowered on a deal present in both"));
        components.add(component(amounts, counts, "SLIPPED", "Left the period, or close date moved later"));
        components.add(component(amounts, counts, "PULLED_IN", "Close date moved earlier"));

        return new Waterfall((String) to.get("period_code"),
                ((java.sql.Date) from.get("captured_on")).toLocalDate(),
                ((java.sql.Date) to.get("captured_on")).toLocalDate(),
                opening, closing, netChange, components, residual,
                residual.compareTo(BigDecimal.ZERO) == 0,
                "Components are computed from immutable snapshot lines. Any residual is shown"
                        + " explicitly rather than absorbed into a component (FR-FCT-006).");
    }

    // ------------------------------------------------------------------ retention

    /**
     * Delete snapshot rows past their retention window.
     *
     * <p>The only caller permitted to delete a snapshot, and it says so to the
     * database: {@code set local app.snapshot_retention_sweep = 'on'} for this
     * transaction only. Every other DELETE — a bug, a migration, a helpful script —
     * hits the immutability trigger and fails.
     */
    @Scheduled(cron = "${axiom.analytics.retention-cron:0 40 2 * * *}")
    public void scheduledRetentionSweep() {
        if (!enabled) return;
        tasks.forEachTenant("analytics-snapshot-retention", this::sweepRetention);
    }

    @Transactional
    public int sweepRetention(UUID tenantId) {
        jdbc.execute("set local app.snapshot_retention_sweep = 'on'");
        int removed = 0;
        for (Map<String, Object> policy : jdbc.queryForList("""
                select snapshot_type, retain_days from analytics.snapshot_retention_policy
                 where tenant_id = ?
                """, tenantId)) {
            String type = (String) policy.get("snapshot_type");
            int retainDays = ((Number) policy.get("retain_days")).intValue();
            int deleted = "PIPELINE".equals(type)
                    ? jdbc.update("delete from analytics.pipeline_snapshot"
                            + " where tenant_id = ? and captured_on < current_date - ?::int",
                            tenantId, retainDays)
                    : jdbc.update("delete from analytics.forecast_snapshot"
                            + " where tenant_id = ? and captured_on < current_date - ?::int",
                            tenantId, retainDays);
            removed += deleted;
            if (deleted > 0) {
                jdbc.update("""
                        update analytics.snapshot_retention_policy
                           set last_swept_at = now(), rows_removed = rows_removed + ?, updated_at = now()
                         where tenant_id = ? and snapshot_type = ?
                        """, (long) deleted, tenantId, type);
            }
        }
        return removed;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> retentionPolicies() {
        return jdbc.queryForList("""
                select snapshot_type, retain_days, last_swept_at, rows_removed,
                       (select count(*) from analytics.pipeline_snapshot p
                         where p.tenant_id = r.tenant_id) as pipeline_rows,
                       (select count(*) from analytics.forecast_snapshot s
                         where s.tenant_id = r.tenant_id) as forecast_rows
                  from analytics.snapshot_retention_policy r
                 where r.tenant_id = ?
                 order by snapshot_type
                """, TenantContext.get().tenantId());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> snapshotHeader(UUID tenantId, UUID snapshotId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, captured_on, period_code, commit_amount, best_case_amount,
                       pipeline_amount, omitted_amount
                  from analytics.forecast_snapshot where tenant_id = ? and id = ?
                """, tenantId, snapshotId);
        if (found.isEmpty()) throw new NotFoundException("No forecast snapshot with that id");
        return found.get(0);
    }

    private static BigDecimal total(Map<String, Object> header) {
        return ((BigDecimal) header.get("commit_amount"))
                .add((BigDecimal) header.get("best_case_amount"))
                .add((BigDecimal) header.get("pipeline_amount"))
                .add((BigDecimal) header.get("omitted_amount"));
    }

    private static void add(Map<String, BigDecimal> amounts, Map<String, Integer> counts,
                            String key, BigDecimal delta) {
        amounts.merge(key, delta, BigDecimal::add);
        counts.merge(key, 1, Integer::sum);
    }

    private static WaterfallComponent component(Map<String, BigDecimal> amounts, Map<String, Integer> counts,
                                                String key, String explanation) {
        return new WaterfallComponent(key, amounts.getOrDefault(key, BigDecimal.ZERO),
                counts.getOrDefault(key, 0), explanation);
    }
}
