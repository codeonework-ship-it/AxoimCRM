package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MarketingPerformanceService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MarketingPerformanceService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Snapshot(UUID id, UUID campaignId, int members, int responses, int mqls, int sqls,
                           BigDecimal budget, BigDecimal influencedPipeline, BigDecimal roiPercent,
                           Instant capturedAt) {}

    @Transactional
    public Snapshot capture(UUID campaignId) {
        CrmRole.requireWrite(TenantContext.get().role());
        Map<String, Object> row = jdbc.queryForList("""
                select c.id, c.code, c.budget_amount, c.pipeline_influenced,
                       count(m.id) as members,
                       count(m.id) filter (where m.status in ('RESPONDED','MQL','SQL')) as responses,
                       count(m.id) filter (where m.status = 'MQL') as mqls,
                       count(m.id) filter (where m.status = 'SQL') as sqls
                from marketing.campaign c
                left join marketing.campaign_member m on m.tenant_id = c.tenant_id and m.campaign_id = c.id
                where c.tenant_id = ? and c.id = ? and c.deleted_at is null
                group by c.id, c.code, c.budget_amount, c.pipeline_influenced
                """, TenantContext.get().tenantId(), campaignId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Campaign not found"));
        BigDecimal budget = (BigDecimal) row.get("budget_amount");
        BigDecimal influenced = (BigDecimal) row.get("pipeline_influenced");
        BigDecimal roi = roi(influenced, budget);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into marketing.campaign_performance_snapshot
                  (id, tenant_id, campaign_id, member_count, response_count, mql_count, sql_count,
                   budget_amount, influenced_pipeline, roi_percent, captured_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, TenantContext.get().tenantId(), campaignId,
                number(row, "members"), number(row, "responses"), number(row, "mqls"), number(row, "sqls"),
                budget, influenced, roi, TenantContext.get().userId());
        audit.record("CAMPAIGN_PERFORMANCE_CAPTURED", "CAMPAIGN", campaignId,
                "Captured governed performance snapshot for " + row.get("code"),
                Map.of("snapshotId", id, "roiPercent", roi == null ? "N/A" : roi));
        return latest(campaignId);
    }

    @Transactional(readOnly = true)
    public Snapshot latest(UUID campaignId) {
        List<Snapshot> rows = jdbc.query("""
                select id, campaign_id, member_count, response_count, mql_count, sql_count,
                       budget_amount, influenced_pipeline, roi_percent, captured_at
                from marketing.campaign_performance_snapshot
                where tenant_id = ? and campaign_id = ? order by captured_at desc limit 1
                """, (rs, i) -> new Snapshot(rs.getObject("id", UUID.class), rs.getObject("campaign_id", UUID.class),
                rs.getInt("member_count"), rs.getInt("response_count"), rs.getInt("mql_count"),
                rs.getInt("sql_count"), rs.getBigDecimal("budget_amount"),
                rs.getBigDecimal("influenced_pipeline"), rs.getBigDecimal("roi_percent"),
                rs.getTimestamp("captured_at").toInstant()), TenantContext.get().tenantId(), campaignId);
        if (rows.isEmpty()) throw new NotFoundException("No performance snapshot has been captured for this campaign");
        return rows.getFirst();
    }

    static BigDecimal roi(BigDecimal influenced, BigDecimal budget) {
        if (budget == null || budget.signum() == 0) return null;
        return influenced.subtract(budget).multiply(BigDecimal.valueOf(100))
                .divide(budget, 2, RoundingMode.HALF_UP);
    }

    private static int number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).intValue();
    }
}
