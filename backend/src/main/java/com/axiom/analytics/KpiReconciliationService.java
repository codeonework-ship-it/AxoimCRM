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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Independent recomputation of the materialized KPI inputs.
 *
 * <p>The authoritative SQL intentionally does not reuse {@link ReadModelProjector}
 * or {@link KpiCalculationService}. Sharing their calculation path would create a
 * check that agrees with the defect it is intended to find. These checks cover the
 * contract-value and closed-revenue foundations used by the governed KPI registry;
 * period, access-scope and presentation behaviour remain covered by the KPI unit
 * suite.</p>
 */
@Service
public class KpiReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(KpiReconciliationService.class);

    public record KpiCheck(String metricCode, BigDecimal projected, BigDecimal authoritative,
                           BigDecimal drift, String status, String detail, long durationMs) {}
    public record KpiReport(UUID runGroupId, Instant runAt, int checksRun, int matched, int drifted,
                            List<KpiCheck> checks, String verdict) {}

    private record Definition(String code, String projectedSql, String authoritativeSql) {}

    private static final List<Definition> CHECKS = List.of(
            new Definition("WIN_RATE",
                    "select count(*) filter (where is_won = true)::numeric / "
                            + "nullif(count(*) filter (where is_closed = true), 0) "
                            + "from analytics.opportunity_fact where tenant_id = ?",
                    "select count(*) filter (where is_won = true)::numeric / "
                            + "nullif(count(*) filter (where is_closed = true), 0) "
                            + "from sales.opportunity where tenant_id = ?"),
            new Definition("AVERAGE_DEAL_SIZE",
                    "select sum(amount) filter (where is_won = true) / "
                            + "nullif(count(*) filter (where is_won = true), 0) "
                            + "from analytics.opportunity_fact where tenant_id = ?",
                    "select sum(amount) filter (where is_won = true) / "
                            + "nullif(count(*) filter (where is_won = true), 0) "
                            + "from sales.opportunity where tenant_id = ?"),
            new Definition("ACV",
                    "select coalesce(sum(acv) filter (where is_won = true), 0) "
                            + "from analytics.opportunity_fact where tenant_id = ?",
                    "select coalesce(sum(case when recurring_amount is not null and coalesce(term_months,0) > 0 "
                            + "then round(recurring_amount / (term_months / 12.0), 4) end) "
                            + "filter (where is_won = true), 0) from sales.opportunity where tenant_id = ?"),
            new Definition("ARR",
                    "select coalesce(sum(arr) filter (where is_won = true), 0) "
                            + "from analytics.opportunity_fact where tenant_id = ?",
                    "select coalesce(sum(arr) filter (where is_won = true), 0) "
                            + "from sales.opportunity where tenant_id = ?"),
            new Definition("TCV",
                    "select coalesce(sum(tcv) filter (where is_won = true), 0) "
                            + "from analytics.opportunity_fact where tenant_id = ?",
                    "select coalesce(sum(tcv) filter (where is_won = true), 0) "
                            + "from sales.opportunity where tenant_id = ?"),
            new Definition("MQL_SQL_CONVERSION",
                    "select count(*) filter (where status in ('QUALIFIED','CONVERTED') or is_converted)::numeric / "
                            + "nullif(count(*) filter (where status in ('QUALIFIED','CONVERTED','DISQUALIFIED') "
                            + "or is_converted or is_disqualified), 0) "
                            + "from analytics.lead_fact where tenant_id = ?",
                    "select count(*) filter (where upper(status) in ('QUALIFIED','CONVERTED') "
                            + "or converted_at is not null)::numeric / "
                            + "nullif(count(*) filter (where upper(status) in ('QUALIFIED','CONVERTED','DISQUALIFIED') "
                            + "or converted_at is not null or disqualified_at is not null), 0) "
                            + "from crm.lead where tenant_id = ? and deleted_at is null")
    );

    private final JdbcTemplate jdbc;
    private final SystemTaskRunner tasks;
    private final AuditService audit;
    private final boolean enabled;

    @Autowired
    public KpiReconciliationService(JdbcTemplate jdbc, SystemTaskRunner tasks, AuditService audit,
                                    @Value("${axiom.analytics.kpi-reconciliation-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.audit = audit;
        this.enabled = enabled;
    }

    KpiReconciliationService(JdbcTemplate jdbc, SystemTaskRunner tasks, AuditService audit) {
        this(jdbc, tasks, audit, true);
    }

    @Scheduled(cron = "${axiom.analytics.kpi-reconciliation-cron:0 30 3 * * *}")
    public void scheduled() {
        if (!enabled) return;
        tasks.forEachTenant("analytics-kpi-reconciliation", tenantId -> {
            KpiReport report = reconcile(tenantId);
            if (report.drifted() > 0) log.error("KPI drift detected for tenant {}: {}", tenantId, report.verdict());
            return report.drifted();
        });
    }

    @Transactional
    public KpiReport reconcileCurrentTenant() {
        UUID tenantId = TenantContext.get().tenantId();
        KpiReport report = reconcile(tenantId);
        audit.record("ANALYTICS_KPI_RECONCILIATION", "KPI", report.runGroupId(), report.verdict(),
                Map.of("checksRun", report.checksRun(), "drifted", report.drifted()));
        return report;
    }

    @Transactional
    public KpiReport reconcile(UUID tenantId) {
        UUID group = UUID.randomUUID();
        Instant runAt = Instant.now();
        List<KpiCheck> results = new ArrayList<>();
        int matched = 0;
        for (Definition definition : CHECKS) {
            long started = System.nanoTime();
            KpiCheck result;
            try {
                BigDecimal projected = jdbc.queryForObject(definition.projectedSql(), BigDecimal.class, tenantId);
                BigDecimal authoritative = jdbc.queryForObject(definition.authoritativeSql(), BigDecimal.class, tenantId);
                boolean equal = projected == null ? authoritative == null
                        : authoritative != null && projected.compareTo(authoritative) == 0;
                BigDecimal drift = projected == null || authoritative == null
                        ? null : projected.subtract(authoritative);
                if (equal) matched++;
                result = new KpiCheck(definition.code(), projected, authoritative, drift,
                        equal ? "MATCH" : "DRIFT",
                        equal ? "Projected KPI equals independent authoritative recomputation."
                                : "KPI differs from authoritative recomputation. Rebuild projections and investigate the formula if drift remains.",
                        elapsed(started));
            } catch (RuntimeException ex) {
                result = new KpiCheck(definition.code(), null, null, null, "ERROR",
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), elapsed(started));
            }
            results.add(result);
            jdbc.update("""
                    insert into analytics.kpi_reconciliation_run
                      (tenant_id, run_group_id, metric_code, projected, authoritative, drift,
                       status, detail, duration_ms, run_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, group, result.metricCode(), result.projected(), result.authoritative(),
                    result.drift(), result.status(), result.detail(), (int) result.durationMs(),
                    java.sql.Timestamp.from(runAt));
        }
        int drifted = results.size() - matched;
        String verdict = drifted == 0
                ? "All " + results.size() + " KPI reconciliation checks matched."
                : drifted + " of " + results.size() + " KPI checks drifted or failed.";
        return new KpiReport(group, runAt, results.size(), matched, drifted, List.copyOf(results), verdict);
    }

    @Transactional(readOnly = true)
    public List<KpiCheck> recent(int limit) {
        return jdbc.query("""
                select metric_code, projected, authoritative, drift, status, detail, duration_ms
                  from analytics.kpi_reconciliation_run where tenant_id = ?
                 order by run_at desc, metric_code limit ?
                """, (rs, row) -> new KpiCheck(rs.getString("metric_code"), rs.getBigDecimal("projected"),
                rs.getBigDecimal("authoritative"), rs.getBigDecimal("drift"), rs.getString("status"),
                rs.getString("detail"), rs.getLong("duration_ms")), TenantContext.get().tenantId(),
                Math.min(Math.max(limit, 1), 200));
    }

    private static long elapsed(long started) {
        return Math.max(0, Math.round((System.nanoTime() - started) / 1_000_000.0));
    }
}
