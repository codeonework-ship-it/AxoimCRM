package com.axiom.workspaces;

import com.axiom.api.PageResult;
import com.axiom.api.QueryService;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class EpicWorkspaceService {
    private final JdbcTemplate jdbc;

    public EpicWorkspaceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SummaryMetric(String label, String value, String unit, String tone) {}
    public record WorkspaceRow(UUID id, String code, String title, String subtitle, String status,
                               String ownerName, BigDecimal amount, LocalDate targetDate,
                               OffsetDateTime updatedAt, Map<String, Object> metrics) {}
    public record WorkspacePage(String moduleCode, String title, String description,
                                List<SummaryMetric> summary, PageResult<WorkspaceRow> rows) {}

    public WorkspacePage forecast(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("s", search, status, args,
                "p.label", "s.forecast_category", "u.display_name", "coalesce(s.manager_note,'')");
        long total = total("""
                select count(*) from forecasting.forecast_submission s
                join forecasting.forecast_period p on p.tenant_id = s.tenant_id and p.id = s.period_id
                join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select s.id, p.code, p.label || ' · ' || s.forecast_category as title,
                       'Confidence ' || s.confidence_pct || '% · risk count ' || s.risk_count as subtitle,
                       s.status, u.display_name as owner_name, s.submitted_amount as amount,
                       p.period_end as target_date, s.submitted_at as updated_at,
                       jsonb_build_object('weightedPipeline', s.weighted_pipeline_amount, 'confidencePct', s.confidence_pct, 'riskCount', s.risk_count) as metrics
                from forecasting.forecast_submission s
                join forecasting.forecast_period p on p.tenant_id = s.tenant_id and p.id = s.period_id
                join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.owner_id
                """ + where + " order by p.period_start desc, s.submitted_amount desc limit ? offset ?", args, safePage);
        return new WorkspacePage("FORECASTING", "Forecast", "Submitted forecasts, weighted pipeline and risk signals.",
                List.of(metric("Submitted", "submitted_amount", "forecasting.forecast_submission", "status <> 'DRAFT'"),
                        metric("Weighted", "weighted_pipeline_amount", "forecasting.forecast_submission", "true"),
                        countMetric("At risk", "forecasting.forecast_submission", "risk_count > 0", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage contracts(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("c", search, status, args, "c.contract_number", "c.title", "a.name", "u.display_name");
        long total = total("""
                select count(*) from contracting.contract_record c
                join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select c.id, c.contract_number as code, c.title,
                       a.name || ' · renewal notice ' || coalesce(c.renewal_notice_date::text, 'not set') as subtitle,
                       c.status, u.display_name as owner_name, c.total_contract_value as amount,
                       c.end_date as target_date, c.updated_at,
                       jsonb_build_object('autoRenew', c.auto_renew, 'startDate', c.start_date, 'orderCount', count(o.id), 'subscriptionCount', count(s.id)) as metrics
                from contracting.contract_record c
                join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                left join contracting.order_record o on o.tenant_id = c.tenant_id and o.contract_id = c.id
                left join contracting.subscription s on s.tenant_id = c.tenant_id and s.contract_id = c.id
                """ + where + """
                 group by c.id, c.contract_number, c.title, a.name, c.renewal_notice_date, c.status, u.display_name,
                         c.total_contract_value, c.end_date, c.updated_at, c.auto_renew, c.start_date
                order by c.end_date, c.total_contract_value desc limit ? offset ?""", args, safePage);
        return new WorkspacePage("CONTRACTING", "Contracts", "Contracts, orders, subscriptions and renewal risk.",
                List.of(metric("TCV", "total_contract_value", "contracting.contract_record", "deleted_at is null"),
                        countMetric("Active", "contracting.contract_record", "status = 'ACTIVE' and deleted_at is null", "good"),
                        countMetric("Renewal risk", "contracting.contract_record", "renewal_notice_date <= current_date + interval '60 days' and deleted_at is null", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage campaigns(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("c", search, status, args, "c.code", "c.name", "c.campaign_type", "u.display_name");
        long total = total("""
                select count(*) from marketing.campaign c
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select c.id, c.code, c.name as title,
                       c.campaign_type || ' · members ' || count(m.id) || ' · responded ' || count(m.id) filter (where m.status in ('RESPONDED','MQL','SQL')) as subtitle,
                       c.status, u.display_name as owner_name, c.pipeline_influenced as amount,
                       coalesce(c.end_date, c.start_date) as target_date, c.created_at as updated_at,
                       jsonb_build_object('budget', c.budget_amount, 'members', count(m.id), 'responses', count(m.id) filter (where m.status in ('RESPONDED','MQL','SQL'))) as metrics
                from marketing.campaign c
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                left join marketing.campaign_member m on m.tenant_id = c.tenant_id and m.campaign_id = c.id
                """ + where + """
                 group by c.id, c.code, c.name, c.campaign_type, c.status, u.display_name, c.pipeline_influenced,
                         c.end_date, c.start_date, c.created_at, c.budget_amount
                order by c.start_date desc, c.pipeline_influenced desc limit ? offset ?""", args, safePage);
        return new WorkspacePage("MARKETING", "Campaigns", "Campaign performance, members and influenced pipeline.",
                List.of(metric("Influenced", "pipeline_influenced", "marketing.campaign", "deleted_at is null"),
                        metric("Budget", "budget_amount", "marketing.campaign", "deleted_at is null"),
                        countMetric("Active", "marketing.campaign", "status = 'ACTIVE' and deleted_at is null", "good")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage cases(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("c", search, status, args, "c.case_number", "c.subject", "a.name", "u.display_name");
        long total = total("""
                select count(*) from service.case_record c
                join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select c.id, c.case_number as code, c.subject as title,
                       a.name || ' · ' || c.priority || ' · ' || c.origin as subtitle,
                       c.status, u.display_name as owner_name, null::numeric as amount,
                       c.resolution_due_at::date as target_date, c.opened_at as updated_at,
                       jsonb_build_object('priority', c.priority, 'origin', c.origin, 'openMilestones', count(m.id) filter (where m.status = 'OPEN'), 'missedMilestones', count(m.id) filter (where m.status = 'MISSED')) as metrics
                from service.case_record c
                join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                left join service.case_milestone m on m.tenant_id = c.tenant_id and m.case_id = c.id
                """ + where + """
                 group by c.id, c.case_number, c.subject, a.name, c.priority, c.origin, c.status, u.display_name,
                         c.resolution_due_at, c.opened_at
                order by case c.priority when 'URGENT' then 1 when 'HIGH' then 2 when 'NORMAL' then 3 else 4 end, c.opened_at desc
                limit ? offset ?""", args, safePage);
        return new WorkspacePage("SERVICE", "Cases", "Customer service cases, entitlements and SLA milestones.",
                List.of(countMetric("Open", "service.case_record", "status not in ('RESOLVED','CLOSED') and deleted_at is null", "warn"),
                        countMetric("Escalated", "service.case_record", "status = 'ESCALATED' and deleted_at is null", "crit"),
                        countMetric("Missed SLA", "service.case_milestone", "status = 'MISSED'", "crit")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage migrations(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("b", search, status, args, "b.batch_number", "b.object_type", "b.file_name", "u.display_name");
        long total = total("""
                select count(*) from migration.import_batch b
                join identity.app_user u on u.tenant_id = b.tenant_id and u.id = b.uploaded_by
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select b.id, b.batch_number as code, b.file_name as title,
                       b.object_type || ' · valid ' || b.valid_rows || '/' || b.total_rows || ' · errors ' || b.error_rows as subtitle,
                       b.status, u.display_name as owner_name, b.imported_rows::numeric as amount,
                       b.uploaded_at::date as target_date, coalesce(b.completed_at, b.uploaded_at) as updated_at,
                       jsonb_build_object('totalRows', b.total_rows, 'validRows', b.valid_rows, 'errorRows', b.error_rows, 'duplicateRows', b.duplicate_rows, 'errors', count(e.id)) as metrics
                from migration.import_batch b
                join identity.app_user u on u.tenant_id = b.tenant_id and u.id = b.uploaded_by
                left join migration.validation_error e on e.tenant_id = b.tenant_id and e.batch_id = b.id
                """ + where + """
                 group by b.id, b.batch_number, b.file_name, b.object_type, b.valid_rows, b.total_rows, b.error_rows,
                         b.status, u.display_name, b.imported_rows, b.uploaded_at, b.completed_at, b.duplicate_rows
                order by b.uploaded_at desc limit ? offset ?""", args, safePage);
        return new WorkspacePage("MIGRATION", "Migration", "Import batches, validation quality and onboarding readiness.",
                List.of(countMetric("Batches", "migration.import_batch", "true", "good"),
                        countMetric("Ready", "migration.import_batch", "status = 'READY_TO_IMPORT'", "good"),
                        countMetric("Errors", "migration.validation_error", "severity = 'ERROR'", "crit")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    private List<WorkspaceRow> query(String sql, List<Object> args, int safePage) {
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(QueryService.PAGE_SIZE);
        pageArgs.add(safePage * QueryService.PAGE_SIZE);
        return jdbc.query(sql, (rs, i) -> new WorkspaceRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("title"),
                rs.getString("subtitle"),
                rs.getString("status"),
                rs.getString("owner_name"),
                rs.getBigDecimal("amount"),
                rs.getObject("target_date", LocalDate.class),
                offsetDateTime(rs.getObject("updated_at")),
                jsonMap(rs.getString("metrics"))),
                pageArgs.toArray());
    }

    private SummaryMetric metric(String label, String column, String table, String predicate) {
        BigDecimal value = jdbc.queryForObject("select coalesce(sum(" + column + "), 0) from " + table + " where tenant_id = ? and " + predicate,
                BigDecimal.class, tenantId());
        return new SummaryMetric(label, value == null ? "0" : value.toPlainString(), "money", "good");
    }

    private SummaryMetric countMetric(String label, String table, String predicate, String tone) {
        Long value = jdbc.queryForObject("select count(*) from " + table + " where tenant_id = ? and " + predicate,
                Long.class, tenantId());
        return new SummaryMetric(label, String.valueOf(value == null ? 0 : value), "count", tone);
    }

    private String where(String alias, String search, String status, List<Object> args, String... searchColumns) {
        StringBuilder where = new StringBuilder(" where " + alias + ".tenant_id = ?");
        String q = searchPattern(search);
        if (q != null) {
            where.append(" and (");
            for (int i = 0; i < searchColumns.length; i++) {
                if (i > 0) where.append(" or ");
                where.append("lower(coalesce(").append(searchColumns[i]).append(",'')) like ?");
                args.add(q);
            }
            where.append(")");
        }
        String f = clean(status);
        if (f != null) {
            where.append(" and upper(").append(alias).append(".status) = ?");
            args.add(f.toUpperCase(Locale.ROOT));
        }
        return where.toString();
    }

    private long total(String sql, List<Object> args) {
        Long value = jdbc.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0 : value;
    }

    private UUID tenantId() {
        return TenantContext.get().tenantId();
    }

    private String searchPattern(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : "%" + cleaned.toLowerCase(Locale.ROOT) + "%";
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private OffsetDateTime offsetDateTime(Object value) {
        if (value instanceof OffsetDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of("raw", json);
        }
    }
}
