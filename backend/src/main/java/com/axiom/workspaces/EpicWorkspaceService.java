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

    public WorkspacePage partners(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("p", search, status, args, "p.partner_code", "a.name", "p.tier", "p.territory_scope", "u.display_name");
        long total = total("""
                select count(*) from channel.partner_account p
                join crm.account a on a.tenant_id = p.tenant_id and a.id = p.account_id
                join identity.app_user u on u.tenant_id = p.tenant_id and u.id = p.manager_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select p.id, p.partner_code as code, a.name as title,
                       p.tier || ' partner - ' || p.territory_scope || ' - active deals ' || p.active_deal_count as subtitle,
                       p.status, u.display_name as owner_name, p.sourced_pipeline as amount,
                       max(r.protection_expires_at)::date as target_date, p.created_at as updated_at,
                       jsonb_build_object('tier', p.tier, 'influencedPipeline', p.influenced_pipeline, 'registrations', count(r.id), 'openConflicts', count(c.id) filter (where c.status = 'OPEN')) as metrics
                from channel.partner_account p
                join crm.account a on a.tenant_id = p.tenant_id and a.id = p.account_id
                join identity.app_user u on u.tenant_id = p.tenant_id and u.id = p.manager_id
                left join channel.deal_registration r on r.tenant_id = p.tenant_id and r.partner_account_id = p.id
                left join channel.channel_conflict c on c.tenant_id = p.tenant_id and c.deal_registration_id = r.id
                """ + where + """
                 group by p.id, p.partner_code, a.name, p.tier, p.territory_scope, p.active_deal_count, p.status,
                         u.display_name, p.sourced_pipeline, p.influenced_pipeline, p.created_at
                order by p.sourced_pipeline desc, a.name limit ? offset ?""", args, safePage);
        return new WorkspacePage("CHANNEL", "Partners", "Partner accounts, registered deals and channel conflict evidence.",
                List.of(metric("Sourced", "sourced_pipeline", "channel.partner_account", "deleted_at is null"),
                        metric("Influenced", "influenced_pipeline", "channel.partner_account", "deleted_at is null"),
                        countMetric("Open conflicts", "channel.channel_conflict", "status = 'OPEN'", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage automation(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("r", search, status, args, "r.rule_code", "r.name", "r.trigger_type", "r.object_type", "u.display_name");
        long total = total("""
                select count(*) from automation.automation_rule r
                join identity.app_user u on u.tenant_id = r.tenant_id and u.id = r.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select r.id, r.rule_code as code, r.name as title,
                       r.trigger_type || ' - ' || r.object_type || ' - version ' || r.active_version as subtitle,
                       r.status, u.display_name as owner_name, r.run_count::numeric as amount,
                       r.last_run_at::date as target_date, r.updated_at,
                       jsonb_build_object('version', r.active_version, 'simulationPassed', r.simulation_passed, 'steps', count(s.id), 'errors', coalesce(sum(run.error_count), 0)) as metrics
                from automation.automation_rule r
                join identity.app_user u on u.tenant_id = r.tenant_id and u.id = r.owner_id
                left join automation.automation_step s on s.tenant_id = r.tenant_id and s.rule_id = r.id
                left join automation.automation_run run on run.tenant_id = r.tenant_id and run.rule_id = r.id
                """ + where + """
                 group by r.id, r.rule_code, r.name, r.trigger_type, r.object_type, r.active_version, r.status,
                         u.display_name, r.run_count, r.last_run_at, r.updated_at, r.simulation_passed
                order by r.status, r.last_run_at desc nulls last limit ? offset ?""", args, safePage);
        return new WorkspacePage("AUTOMATION", "Automation", "Rules, simulations, approvals and execution trace health.",
                List.of(countMetric("Active rules", "automation.automation_rule", "status = 'ACTIVE'", "good"),
                        countMetric("Runs", "automation.automation_run", "true", "good"),
                        countMetric("Errored runs", "automation.automation_run", "error_count > 0", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage analytics(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("d", search, status, args, "d.dashboard_code", "d.name", "u.display_name");
        long total = total("""
                select count(*) from reporting.analytics_dashboard d
                join identity.app_user u on u.tenant_id = d.tenant_id and u.id = d.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select d.id, d.dashboard_code as code, d.name as title,
                       'Refresh every ' || d.refresh_interval_minutes || ' minutes - widgets ' || count(w.id) as subtitle,
                       d.status, u.display_name as owner_name, coalesce(sum(w.metric_value), 0) as amount,
                       d.last_refreshed_at::date as target_date, d.last_refreshed_at as updated_at,
                       jsonb_build_object('widgets', count(w.id), 'refreshMinutes', d.refresh_interval_minutes, 'kpiWidgets', count(w.id) filter (where w.visualization_type = 'KPI')) as metrics
                from reporting.analytics_dashboard d
                join identity.app_user u on u.tenant_id = d.tenant_id and u.id = d.owner_id
                left join reporting.dashboard_widget w on w.tenant_id = d.tenant_id and w.dashboard_id = d.id
                """ + where + """
                 group by d.id, d.dashboard_code, d.name, d.refresh_interval_minutes, d.status, u.display_name, d.last_refreshed_at
                order by d.last_refreshed_at desc nulls last limit ? offset ?""", args, safePage);
        return new WorkspacePage("ANALYTICS", "Analytics", "Dashboards, widgets, governed KPI definitions and refresh health.",
                List.of(countMetric("Dashboards", "reporting.analytics_dashboard", "status = 'ACTIVE'", "good"),
                        countMetric("Widgets", "reporting.dashboard_widget", "true", "good"),
                        metric("KPI value", "current_value", "reporting.kpi_definition", "status = 'ACTIVE'")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage copilot(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("r", search, status, args, "r.recommendation_number", "r.title", "r.related_entity_type", "p.title", "r.explanation");
        long total = total("""
                select count(*) from ai.copilot_recommendation r
                join ai.copilot_prompt p on p.tenant_id = r.tenant_id and p.id = r.prompt_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select r.id, r.recommendation_number as code, r.title,
                       p.use_case || ' - ' || r.related_entity_type || ' - citations ' || count(c.id) as subtitle,
                       r.status, p.title as owner_name, r.confidence_pct as amount,
                       r.expires_at::date as target_date, r.created_at as updated_at,
                       jsonb_build_object('confidencePct', r.confidence_pct, 'prompt', p.prompt_code, 'modelPolicy', p.model_policy, 'citations', count(c.id)) as metrics
                from ai.copilot_recommendation r
                join ai.copilot_prompt p on p.tenant_id = r.tenant_id and p.id = r.prompt_id
                left join ai.grounding_citation c on c.tenant_id = r.tenant_id and c.recommendation_id = r.id
                """ + where + """
                 group by r.id, r.recommendation_number, r.title, p.use_case, r.related_entity_type, r.status,
                         p.title, r.confidence_pct, r.expires_at, r.created_at, p.prompt_code, p.model_policy
                order by r.created_at desc limit ? offset ?""", args, safePage);
        return new WorkspacePage("AI", "AI Copilot", "Grounded prompts, recommendations, confidence and citation evidence.",
                List.of(countMetric("Ready", "ai.copilot_recommendation", "status = 'READY'", "good"),
                        countMetric("Citations", "ai.grounding_citation", "true", "good"),
                        countMetric("Disabled prompts", "ai.copilot_prompt", "status = 'DISABLED'", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage mobile(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("d", search, status, args, "d.device_label", "d.platform", "u.display_name", "d.app_version");
        long total = total("""
                select count(*) from mobile.device_session d
                join identity.app_user u on u.tenant_id = d.tenant_id and u.id = d.user_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select d.id, d.device_label as code, u.display_name || ' - ' || d.platform as title,
                       'Offline queue ' || d.offline_queue_count || ' - app ' || d.app_version as subtitle,
                       d.status, u.display_name as owner_name, d.offline_queue_count::numeric as amount,
                       d.last_sync_at::date as target_date, d.last_sync_at as updated_at,
                       jsonb_build_object('platform', d.platform, 'appVersion', d.app_version, 'packages', count(p.id), 'conflicts', count(p.id) filter (where p.status = 'CONFLICT')) as metrics
                from mobile.device_session d
                join identity.app_user u on u.tenant_id = d.tenant_id and u.id = d.user_id
                left join mobile.offline_sync_package p on p.tenant_id = d.tenant_id and p.device_session_id = d.id
                """ + where + """
                 group by d.id, d.device_label, u.display_name, d.platform, d.offline_queue_count, d.app_version, d.status, d.last_sync_at
                order by d.last_sync_at desc nulls last limit ? offset ?""", args, safePage);
        return new WorkspacePage("MOBILE", "Mobile", "Responsive/mobile profiles, device sessions and offline sync packages.",
                List.of(countMetric("Active devices", "mobile.device_session", "status = 'ACTIVE'", "good"),
                        countMetric("Offline profiles", "mobile.mobile_profile", "status = 'ACTIVE'", "good"),
                        countMetric("Sync conflicts", "mobile.offline_sync_package", "status in ('CONFLICT','FAILED')", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage integrations(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("c", search, status, args, "c.contract_code", "c.name", "c.direction", "c.auth_type", "u.display_name");
        long total = total("""
                select count(*) from integration.endpoint_contract c
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select c.id, c.contract_code as code, c.name as title,
                       c.direction || ' - ' || c.auth_type || ' - failures ' || c.failure_count as subtitle,
                       c.status, u.display_name as owner_name, coalesce(sum(j.records_processed), 0)::numeric as amount,
                       c.last_verified_at::date as target_date, c.created_at as updated_at,
                       jsonb_build_object('jobs', count(j.id), 'failedRecords', coalesce(sum(j.records_failed), 0), 'webhooks', count(w.id), 'deliveryFailures', coalesce(sum(w.delivery_failures), 0)) as metrics
                from integration.endpoint_contract c
                join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                left join integration.integration_job j on j.tenant_id = c.tenant_id and j.endpoint_contract_id = c.id
                left join integration.webhook_subscription_stub w on w.tenant_id = c.tenant_id and w.endpoint_contract_id = c.id
                """ + where + """
                 group by c.id, c.contract_code, c.name, c.direction, c.auth_type, c.failure_count, c.status,
                         u.display_name, c.last_verified_at, c.created_at
                order by c.last_verified_at desc nulls last limit ? offset ?""", args, safePage);
        return new WorkspacePage("INTEGRATION", "Integrations", "Endpoint contracts, integration jobs and webhook subscription stubs.",
                List.of(countMetric("Active contracts", "integration.endpoint_contract", "status = 'ACTIVE'", "good"),
                        countMetric("Retrying jobs", "integration.integration_job", "status = 'RETRYING'", "warn"),
                        countMetric("Active webhooks", "integration.webhook_subscription_stub", "status = 'ACTIVE'", "good")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage sandbox(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("s", search, status, args, "s.sandbox_code", "s.name", "s.sandbox_type", "s.source_environment", "u.display_name");
        long total = total("""
                select count(*) from platform.sandbox_environment s
                join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select s.id, s.sandbox_code as code, s.name as title,
                       s.sandbox_type || ' from ' || s.source_environment || ' - packages ' || count(p.id) as subtitle,
                       s.status, u.display_name as owner_name, coalesce(sum(p.component_count), 0)::numeric as amount,
                       s.expires_at::date as target_date, coalesce(s.last_refreshed_at, s.created_at) as updated_at,
                       jsonb_build_object('packages', count(p.id), 'deployments', count(d.id), 'validationErrors', coalesce(sum(d.validation_errors), 0)) as metrics
                from platform.sandbox_environment s
                join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.owner_id
                left join platform.release_package p on p.tenant_id = s.tenant_id and p.source_sandbox_id = s.id
                left join platform.deployment_run d on d.tenant_id = p.tenant_id and d.release_package_id = p.id
                """ + where + """
                 group by s.id, s.sandbox_code, s.name, s.sandbox_type, s.source_environment, s.status,
                         u.display_name, s.expires_at, s.last_refreshed_at, s.created_at
                order by s.expires_at nulls last limit ? offset ?""", args, safePage);
        return new WorkspacePage("PLATFORM", "Sandbox & Release", "Sandbox environments, release packages and deployment evidence.",
                List.of(countMetric("Active sandboxes", "platform.sandbox_environment", "status = 'ACTIVE'", "good"),
                        countMetric("Approved releases", "platform.release_package", "status in ('APPROVED','DEPLOYED')", "good"),
                        countMetric("Failed deployments", "platform.deployment_run", "status = 'FAILED'", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage audit(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("p", search, status, args, "p.pack_code", "p.name", "p.scope", "u.display_name");
        long total = total("""
                select count(*) from governance.audit_evidence_pack p
                join identity.app_user u on u.tenant_id = p.tenant_id and u.id = p.generated_by
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select p.id, p.pack_code as code, p.name as title,
                       p.scope || ' - controls ' || p.control_count || ' - events ' || p.event_count as subtitle,
                       p.status, u.display_name as owner_name, p.event_count::numeric as amount,
                       p.generated_at::date as target_date, p.created_at as updated_at,
                       jsonb_build_object('controls', p.control_count, 'reviews', count(r.id), 'findings', coalesce(sum(r.finding_count), 0), 'signalsRed', (select count(*) from governance.observability_signal s where s.tenant_id = p.tenant_id and s.status = 'RED')) as metrics
                from governance.audit_evidence_pack p
                join identity.app_user u on u.tenant_id = p.tenant_id and u.id = p.generated_by
                left join governance.control_review r on r.tenant_id = p.tenant_id and r.evidence_pack_id = p.id
                """ + where + """
                 group by p.id, p.pack_code, p.name, p.scope, p.control_count, p.event_count, p.status,
                         u.display_name, p.generated_at, p.created_at
                order by p.created_at desc limit ? offset ?""", args, safePage);
        return new WorkspacePage("GOVERNANCE", "Audit & Compliance", "Evidence packs, control reviews and observability signals.",
                List.of(countMetric("Ready packs", "governance.audit_evidence_pack", "status in ('READY','EXPORTED')", "good"),
                        countMetric("Open reviews", "governance.control_review", "status in ('SCHEDULED','IN_PROGRESS')", "warn"),
                        countMetric("Amber/red signals", "governance.observability_signal", "status in ('AMBER','RED')", "warn")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage bfsi(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("o", "kyc_status", search, status, args, "o.onboarding_number", "a.name", "o.client_type", "o.risk_rating", "u.display_name");
        long total = total("""
                select count(*) from bfsi.client_onboarding o
                join crm.account a on a.tenant_id = o.tenant_id and a.id = o.account_id
                join identity.app_user u on u.tenant_id = o.tenant_id and u.id = o.owner_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select o.id, o.onboarding_number as code, a.name as title,
                       o.client_type || ' - ' || o.risk_rating || ' risk - holdings ' || count(h.id) as subtitle,
                       o.kyc_status as status, u.display_name as owner_name, coalesce(sum(h.balance_amount), 0) as amount,
                       o.due_at as target_date, o.created_at as updated_at,
                       jsonb_build_object('riskRating', o.risk_rating, 'holdings', count(h.id), 'screenings', count(s.id), 'hits', coalesce(sum(s.hit_count), 0)) as metrics
                from bfsi.client_onboarding o
                join crm.account a on a.tenant_id = o.tenant_id and a.id = o.account_id
                join identity.app_user u on u.tenant_id = o.tenant_id and u.id = o.owner_id
                left join bfsi.product_holding h on h.tenant_id = o.tenant_id and h.onboarding_id = o.id
                left join bfsi.compliance_screening s on s.tenant_id = o.tenant_id and s.onboarding_id = o.id
                """ + where + """
                 group by o.id, o.onboarding_number, a.name, o.client_type, o.risk_rating, o.kyc_status,
                         u.display_name, o.due_at, o.created_at
                order by o.due_at limit ? offset ?""", args, safePage);
        return new WorkspacePage("BFSI", "BFSI", "Financial-services onboarding, holdings and compliance screening.",
                List.of(countMetric("EDD cases", "bfsi.client_onboarding", "kyc_status = 'ENHANCED_DUE_DILIGENCE'", "warn"),
                        metric("Holdings", "balance_amount", "bfsi.product_holding", "status in ('ACTIVE','PROPOSED')"),
                        countMetric("Screening hits", "bfsi.compliance_screening", "status = 'HIT'", "crit")),
                PageResult.of(rows, safePage, QueryService.PAGE_SIZE, total));
    }

    public WorkspacePage commodity(String search, String status, int page) {
        int safePage = Math.max(page, 0);
        List<Object> args = new ArrayList<>(List.of(tenantId()));
        String where = where("e", search, status, args, "e.enquiry_number", "e.commodity_name", "p.counterparty_code", "a.name");
        long total = total("""
                select count(*) from commodity.trade_enquiry e
                join commodity.counterparty_profile p on p.tenant_id = e.tenant_id and p.id = e.counterparty_profile_id
                join crm.account a on a.tenant_id = p.tenant_id and a.id = p.account_id
                """ + where, args);
        List<WorkspaceRow> rows = query("""
                select e.id, e.enquiry_number as code, e.commodity_name || ' - ' || a.name as title,
                       e.quantity || ' ' || e.unit || ' - exposure ' || p.exposure_amount as subtitle,
                       e.status, p.counterparty_code as owner_name, e.notional_amount as amount,
                       e.delivery_window_start as target_date, e.created_at as updated_at,
                       jsonb_build_object('creditLimit', p.credit_limit, 'exposure', p.exposure_amount, 'termSheets', count(t.id), 'counterpartyStatus', p.status) as metrics
                from commodity.trade_enquiry e
                join commodity.counterparty_profile p on p.tenant_id = e.tenant_id and p.id = e.counterparty_profile_id
                join crm.account a on a.tenant_id = p.tenant_id and a.id = p.account_id
                left join commodity.contract_term_sheet t on t.tenant_id = e.tenant_id and t.trade_enquiry_id = e.id
                """ + where + """
                 group by e.id, e.enquiry_number, e.commodity_name, a.name, e.quantity, e.unit, p.exposure_amount,
                         e.status, p.counterparty_code, e.notional_amount, e.delivery_window_start, e.created_at, p.credit_limit, p.status
                order by e.created_at desc limit ? offset ?""", args, safePage);
        return new WorkspacePage("COMMODITY", "Commodity", "Commodity counterparties, enquiries and term sheets.",
                List.of(metric("Notional", "notional_amount", "commodity.trade_enquiry", "true"),
                        metric("Exposure", "exposure_amount", "commodity.counterparty_profile", "true"),
                        countMetric("Watchlist", "commodity.counterparty_profile", "status = 'WATCHLIST'", "warn")),
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
        return where(alias, "status", search, status, args, searchColumns);
    }

    private String where(String alias, String statusColumn, String search, String status, List<Object> args, String... searchColumns) {
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
            where.append(" and upper(").append(alias).append(".").append(statusColumn).append(") = ?");
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
