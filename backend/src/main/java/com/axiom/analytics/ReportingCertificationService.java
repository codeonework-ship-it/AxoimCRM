package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operational closure for P0E15: recover, prove parity, and issue evidence-based certificates. */
@Service
public class ReportingCertificationService {
    public static final long PRODUCTION_MINIMUM_ROWS = 1_000_000L;
    public static final int PRODUCTION_MINIMUM_EXECUTIONS = 50;
    public static final int PRODUCTION_MAXIMUM_P95_MS = 3_000;
    public static final int EVIDENCE_WINDOW_DAYS = 30;

    public record RecoveryReport(ProjectionBackfillService.BackfillRun rebuild,
                                 ReconciliationService.ReconciliationReport projectionReconciliation,
                                 KpiReconciliationService.KpiReport kpiReconciliation,
                                 String status, String verdict) {}

    public record Certification(UUID id, String profile, String status, long projectedRows,
                                long minimumRows, long executions, int minimumExecutions,
                                Integer p95Ms, int maximumP95Ms, Integer maximumMs, long timeouts,
                                int projectionDrifts, int kpiDrifts, int evidenceWindowDays,
                                Instant startedAt, Instant finishedAt, String verdict) {}

    private final JdbcTemplate jdbc;
    private final ProjectionBackfillService backfill;
    private final ReconciliationService reconciliation;
    private final KpiReconciliationService kpiReconciliation;
    private final AuditService audit;
    private final ObjectMapper json;

    public ReportingCertificationService(JdbcTemplate jdbc, ProjectionBackfillService backfill,
                                         ReconciliationService reconciliation,
                                         KpiReconciliationService kpiReconciliation,
                                         AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.backfill = backfill;
        this.reconciliation = reconciliation;
        this.kpiReconciliation = kpiReconciliation;
        this.audit = audit;
        this.json = json;
    }

    /** Rebuild first, then independently prove both projection and KPI parity. */
    public RecoveryReport rebuildAndReconcile(ProjectionBackfillService.BackfillRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        ProjectionBackfillService.BackfillRun rebuild = backfill.run(request);
        ReconciliationService.ReconciliationReport projection = reconciliation.reconcileCurrentTenant();
        KpiReconciliationService.KpiReport kpis = kpiReconciliation.reconcileCurrentTenant();
        boolean passed = "COMPLETED".equals(rebuild.status())
                && projection.checksDrifted() == 0 && kpis.drifted() == 0;
        String verdict = passed
                ? "Read model rebuilt and independently reconciled with zero projection and KPI drift."
                : "Recovery completed with unresolved drift; do not certify reporting until every check matches.";
        return new RecoveryReport(rebuild, projection, kpis, passed ? "PASS" : "FAIL", verdict);
    }

    /**
     * Issue a PRODUCTION certificate only from fresh reconciliation and measured
     * query executions. Thresholds are constants so an API caller cannot lower the
     * bar until a weak environment passes.
     */
    @Transactional
    public Certification certifyProduction() {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        UUID tenantId = TenantContext.get().tenantId();
        UUID id = UUID.randomUUID();
        Instant started = Instant.now();
        ReconciliationService.ReconciliationReport projection = reconciliation.reconcile(tenantId);
        KpiReconciliationService.KpiReport kpis = kpiReconciliation.reconcile(tenantId);

        Map<String, Object> evidence = jdbc.queryForMap("""
                select
                  (select count(*) from analytics.opportunity_fact where tenant_id = ?)
                + (select count(*) from analytics.lead_fact where tenant_id = ?)
                + (select count(*) from analytics.activity_fact where tenant_id = ?)
                + (select count(*) from analytics.account_fact where tenant_id = ?) as projected_rows,
                  count(*) as executions,
                  percentile_disc(0.95) within group (order by elapsed_ms)
                    filter (where status in ('OK','TRUNCATED')) as p95_ms,
                  max(elapsed_ms) filter (where status in ('OK','TRUNCATED')) as maximum_ms,
                  count(*) filter (where status = 'TIMEOUT') as timeouts
                from analytics.query_execution
                where tenant_id = ? and executed_at >= now() - interval '30 days'
                """, tenantId, tenantId, tenantId, tenantId, tenantId);

        long rows = number(evidence.get("projected_rows"));
        long executions = number(evidence.get("executions"));
        Integer p95 = integer(evidence.get("p95_ms"));
        Integer maximum = integer(evidence.get("maximum_ms"));
        long timeouts = number(evidence.get("timeouts"));
        boolean enough = rows >= PRODUCTION_MINIMUM_ROWS && executions >= PRODUCTION_MINIMUM_EXECUTIONS;
        boolean healthy = enough && p95 != null && p95 <= PRODUCTION_MAXIMUM_P95_MS && timeouts == 0
                && projection.checksDrifted() == 0 && kpis.drifted() == 0;
        String status = !enough ? "INSUFFICIENT_EVIDENCE" : healthy ? "PASS" : "FAIL";
        String verdict = switch (status) {
            case "PASS" -> "Production reporting certified: scale, latency, timeout, projection and KPI controls passed.";
            case "FAIL" -> "Production evidence volume is sufficient, but one or more latency, timeout or reconciliation controls failed.";
            default -> "Certification withheld: load at least " + PRODUCTION_MINIMUM_ROWS
                    + " projected rows and record " + PRODUCTION_MINIMUM_EXECUTIONS
                    + " governed query executions inside the evidence window.";
        };
        Instant finished = Instant.now();
        String details = json(Map.of(
                "verdict", verdict,
                "projectionChecks", projection.checksRun(),
                "kpiChecks", kpis.checksRun(),
                "thresholdPolicy", "P0E15_PRODUCTION_V1"));
        jdbc.update("""
                insert into analytics.performance_certification_run
                  (id, tenant_id, profile, status, projected_rows, minimum_rows, executions,
                   minimum_executions, p95_ms, maximum_p95_ms, maximum_ms, timeouts,
                   projection_drifts, kpi_drifts, evidence_window_days, detail, certified_by,
                   started_at, finished_at)
                values (?, ?, 'PRODUCTION', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, id, tenantId, status, rows, PRODUCTION_MINIMUM_ROWS, executions,
                PRODUCTION_MINIMUM_EXECUTIONS, p95, PRODUCTION_MAXIMUM_P95_MS, maximum, timeouts,
                projection.checksDrifted(), kpis.drifted(), EVIDENCE_WINDOW_DAYS, details,
                TenantContext.get().userId(), java.sql.Timestamp.from(started), java.sql.Timestamp.from(finished));
        audit.record("ANALYTICS_PRODUCTION_CERTIFICATION", "REPORTING_CERTIFICATION", id, verdict,
                Map.of("status", status, "projectedRows", rows, "executions", executions,
                        "projectionDrifts", projection.checksDrifted(), "kpiDrifts", kpis.drifted()));
        return new Certification(id, "PRODUCTION", status, rows, PRODUCTION_MINIMUM_ROWS, executions,
                PRODUCTION_MINIMUM_EXECUTIONS, p95, PRODUCTION_MAXIMUM_P95_MS, maximum, timeouts,
                projection.checksDrifted(), kpis.drifted(), EVIDENCE_WINDOW_DAYS, started, finished, verdict);
    }

    @Transactional(readOnly = true)
    public List<Certification> recent(int limit) {
        return jdbc.query("""
                select id, profile, status, projected_rows, minimum_rows, executions,
                       minimum_executions, p95_ms, maximum_p95_ms, maximum_ms, timeouts,
                       projection_drifts, kpi_drifts, evidence_window_days, started_at, finished_at,
                       detail ->> 'verdict' as verdict
                  from analytics.performance_certification_run where tenant_id = ?
                 order by finished_at desc limit ?
                """, (rs, row) -> new Certification(rs.getObject("id", UUID.class), rs.getString("profile"),
                rs.getString("status"), rs.getLong("projected_rows"), rs.getLong("minimum_rows"),
                rs.getLong("executions"), rs.getInt("minimum_executions"), (Integer) rs.getObject("p95_ms"),
                rs.getInt("maximum_p95_ms"), (Integer) rs.getObject("maximum_ms"), rs.getLong("timeouts"),
                rs.getInt("projection_drifts"), rs.getInt("kpi_drifts"), rs.getInt("evidence_window_days"),
                rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("finished_at").toInstant(),
                rs.getString("verdict")), TenantContext.get().tenantId(), Math.min(Math.max(limit, 1), 50));
    }

    private String json(Map<String, ?> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Certification evidence could not be encoded", ex); }
    }

    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }
    private static Integer integer(Object value) { return value == null ? null : ((Number) value).intValue(); }
}
