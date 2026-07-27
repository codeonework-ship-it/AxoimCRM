package com.axiom.reporting;

import com.axiom.auth.CrmRole;
import com.axiom.api.PageResult;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRXlsxExporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleXlsxReportConfiguration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReportService {
    public enum ReportFormat {
        PDF("application/pdf", "pdf"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        final String contentType;
        final String extension;
        ReportFormat(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    public record ReportDefinitionRow(UUID id, String code, String label, String description,
                                      List<String> allowedFormats, boolean active, String category,
                                      String businessQuestion, List<String> audience, int sortOrder) {}
    public record FilePayload(byte[] bytes, String contentType, String filename,
                              String datasetFingerprint, int rowCount) {
        public FilePayload(byte[] bytes, String contentType, String filename) {
            this(bytes, contentType, filename, "UNAVAILABLE", -1);
        }
    }
    public record ReportPreviewColumns(String dimension, String value, String detail, String signal) {}
    public record ReportPreviewRow(String metric, String value, String detail, String signal) {}
    public record ReportFilters(String search, String metric, String value, String detail, String signal) {
        public static final ReportFilters EMPTY = new ReportFilters(null, null, null, null, null);
    }
    public record ReportPreview(String code, String label, String description, String category,
                                String businessQuestion, List<String> audience, String tenantName,
                                OffsetDateTime generatedAt, ReportPreviewColumns columns,
                                PageResult<ReportPreviewRow> rows, String datasetFingerprint,
                                int matchedRowCount) {}
    record ReportSpec(String dimensionLabel, String valueLabel, String detailLabel) {}
    record ResolvedRows(List<ReportRow> rows, String fingerprint) {}

    private static final Map<String, ReportSpec> REPORT_SPECS = Map.ofEntries(
            Map.entry("tenant_summary", new ReportSpec("CRM area", "Current value", "Meaning")),
            Map.entry("pipeline_snapshot", new ReportSpec("Pipeline stage", "Open value", "Deal volume")),
            Map.entry("forecast_commitment", new ReportSpec("Forecast category", "Open value", "Volume and weighted value")),
            Map.entry("pipeline_aging_risk", new ReportSpec("Pipeline stage", "Value exposed", "Stalled and overdue deals")),
            Map.entry("win_loss_analysis", new ReportSpec("Closed outcome", "Closed value", "Volume and average cycle")),
            Map.entry("lead_conversion_funnel", new ReportSpec("Lead status", "Lead volume", "Converted volume and rate")),
            Map.entry("lead_source_conversion", new ReportSpec("Lead source", "Lead volume", "Converted volume and rate")),
            Map.entry("sales_activity_productivity", new ReportSpec("Activity type", "Activity volume", "Completion and time invested")),
            Map.entry("account_health_portfolio", new ReportSpec("Health band", "Accounts", "Revenue exposure and score")),
            Map.entry("customer_service_sla", new ReportSpec("Case priority", "Case volume", "Open and overdue milestones")),
            Map.entry("quote_conversion_margin", new ReportSpec("Quote status", "Commercial value", "Volume and average margin")),
            Map.entry("campaign_roi", new ReportSpec("Campaign", "Influenced pipeline", "Budget, response and return")),
            Map.entry("data_quality_exceptions", new ReportSpec("Data exception", "Affected records", "Why it matters")),
            Map.entry("quota_attainment", new ReportSpec("Representative or territory", "Attainment", "Target, actual and gap")),
            Map.entry("forecast_accuracy_bias", new ReportSpec("Forecast owner and period", "Accuracy", "Submitted, actual and directional bias")),
            Map.entry("stage_conversion_velocity", new ReportSpec("Sales stage", "Forward conversion", "Entries, exits and average elapsed time")),
            Map.entry("renewal_arr_bridge", new ReportSpec("ARR bridge component", "Annual recurring revenue", "Subscription movement basis")),
            Map.entry("pipeline_movement_waterfall", new ReportSpec("Pipeline movement", "Value movement", "Opportunity volume and reconciliation")),
            Map.entry("account_whitespace", new ReportSpec("Customer account", "Whitespace", "Products available for cross-sell")),
            Map.entry("customer_360_brief", new ReportSpec("Customer account", "Current ARR", "Health, pipeline, service and renewal posture")),
            Map.entry("discount_approval_governance", new ReportSpec("Active quote", "Discount leakage", "Commercial value, margin and approval posture"))
    );

    static Set<String> supportedReportCodes() {
        return REPORT_SPECS.keySet();
    }

    public static class ReportRow {
        private final String metric;
        private final String value;
        private final String detail;
        private final String signal;
        public ReportRow(String metric, String value, String detail) {
            this(metric, value, detail, "INFORMATION");
        }
        public ReportRow(String metric, String value, String detail, String signal) {
            this.metric = metric;
            this.value = value;
            this.detail = detail;
            this.signal = signal;
        }
        public String getMetric() { return metric; }
        public String getValue() { return value; }
        public String getDetail() { return detail; }
        public String getSignal() { return signal; }
    }

    private final JdbcTemplate jdbc;
    private final ResourceLoader resources;

    public ReportService(JdbcTemplate jdbc, ResourceLoader resources) {
        this.jdbc = jdbc;
        this.resources = resources;
    }

    @Transactional(readOnly = true)
    public List<ReportDefinitionRow> definitions() {
        return jdbc.query("""
                select id, code, label, description, allowed_formats, active,
                       category, business_question, audience, sort_order
                from reporting.report_definition
                where tenant_id = ? and active = true
                order by category, sort_order, label
                """, (rs, i) -> {
            Object[] raw = (Object[]) rs.getArray("allowed_formats").getArray();
            Object[] audience = (Object[]) rs.getArray("audience").getArray();
            return new ReportDefinitionRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("label"),
                    rs.getString("description"),
                    java.util.Arrays.stream(raw).map(String::valueOf).toList(),
                    rs.getBoolean("active"),
                    rs.getString("category"),
                    rs.getString("business_question"),
                    java.util.Arrays.stream(audience).map(String::valueOf).toList(),
                    rs.getInt("sort_order")
            );
        }, TenantContext.get().tenantId());
    }

    @Transactional
    public FilePayload export(String code, ReportFormat format) {
        return export(code, format, ReportFilters.EMPTY);
    }

    @Transactional
    public FilePayload export(String code, ReportFormat format, ReportFilters filters) {
        CrmRole.requireExport(TenantContext.get().role());
        Map<String, Object> definition = findDefinition(code, format);
        ReportSpec spec = REPORT_SPECS.get(code);
        if (spec == null) throw new NotFoundException("Report query is not implemented");
        ResolvedRows dataset = resolveRows(code, filters);
        List<ReportRow> rows = dataset.rows();
        byte[] bytes = render(definition, spec, format, rows, dataset.fingerprint());
        jdbc.update("""
                insert into reporting.report_run(tenant_id, report_definition_id, format, status, row_count, generated_by)
                values (?, ?, ?, 'GENERATED', ?, ?)
                """, TenantContext.get().tenantId(), definition.get("id"), format.name(), rows.size(), TenantContext.get().userId());
        String fileCode = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return new FilePayload(bytes, format.contentType, fileCode + "." + format.extension,
                dataset.fingerprint(), rows.size());
    }

    /**
     * Render the selected report as an inline PDF for the authenticated viewer.
     *
     * <p>This is deliberately a read use case, separate from {@link #export}:
     * opening a preview must not create export evidence or require an export
     * entitlement. Tenant isolation and the report definition's active/format
     * contract still apply because this path resolves the same definition and
     * executes the same tenant-scoped query supplied to Jasper.
     */
    @Transactional(readOnly = true)
    public FilePayload documentPreview(String code) {
        return documentPreview(code, ReportFilters.EMPTY);
    }

    @Transactional(readOnly = true)
    public FilePayload documentPreview(String code, ReportFilters filters) {
        Map<String, Object> definition = findDefinition(code, ReportFormat.PDF);
        ReportSpec spec = REPORT_SPECS.get(code);
        if (spec == null) throw new NotFoundException("Report query is not implemented");
        ResolvedRows dataset = resolveRows(code, filters);
        byte[] bytes = render(definition, spec, ReportFormat.PDF, dataset.rows(), dataset.fingerprint());
        String fileCode = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return new FilePayload(bytes, ReportFormat.PDF.contentType, fileCode + "-preview.pdf",
                dataset.fingerprint(), dataset.rows().size());
    }

    /**
     * Browser-safe, accessible representation of the same tenant-scoped rows
     * supplied to Jasper. A native PDF plugin is not a dependable preview
     * surface in hardened browsers, Electron or mobile WebViews; returning the
     * semantic rows keeps the entire report readable without inventing a second
     * calculation path. Export permission remains independently enforced by
     * {@link #export(String, ReportFormat)}.
     */
    @Transactional(readOnly = true)
    public ReportPreview preview(String code, int requestedPage, int requestedSize, ReportFilters filters) {
        Map<String, Object> definition = findDefinition(code, ReportFormat.PDF);
        ReportSpec spec = REPORT_SPECS.get(code);
        if (spec == null) throw new NotFoundException("Report query is not implemented");
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(100, requestedSize));
        ResolvedRows dataset = resolveRows(code, filters);
        List<ReportPreviewRow> matchedRows = dataset.rows().stream()
                .map(row -> new ReportPreviewRow(row.getMetric(), row.getValue(), row.getDetail(), row.getSignal()))
                .toList();
        int from = (int) Math.min((long) page * size, matchedRows.size());
        int to = Math.min(from + size, matchedRows.size());
        PageResult<ReportPreviewRow> rows = PageResult.of(matchedRows.subList(from, to), page, size, matchedRows.size());
        String tenantName = jdbc.queryForObject(
                "select name from platform.tenant where id = ?", String.class, TenantContext.get().tenantId());
        return new ReportPreview(
                code,
                String.valueOf(definition.get("label")),
                String.valueOf(definition.get("description")),
                String.valueOf(definition.get("category")),
                String.valueOf(definition.get("business_question")),
                sqlArray(definition.get("audience")),
                tenantName,
                OffsetDateTime.now(),
                new ReportPreviewColumns(spec.dimensionLabel(), spec.valueLabel(), spec.detailLabel(), "Signal"),
                rows,
                dataset.fingerprint(),
                matchedRows.size()
        );
    }

    private ResolvedRows resolveRows(String code, ReportFilters filters) {
        List<ReportRow> rows = List.copyOf(filterRows(rowsFor(code), filters));
        return new ResolvedRows(rows, datasetFingerprint(rows));
    }

    /** Stable content identity shared by grid, PDF, Excel and Word regression checks. */
    static String datasetFingerprint(List<ReportRow> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ReportRow row : rows) {
                updateDigest(digest, row.getMetric());
                updateDigest(digest, row.getValue());
                updateDigest(digest, row.getDetail());
                updateDigest(digest, row.getSignal());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", ex);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    /**
     * Apply the report-grid query contract on the server. Global search is an
     * OR across all visible columns; column filters are ANDed and use the same
     * case-insensitive "contains" rule as the rest of Axiom's text grids.
     * Exports and PDF preview call this method too, so a filtered screen cannot
     * silently produce an unrelated document.
     */
    static List<ReportRow> filterRows(List<ReportRow> rows, ReportFilters requested) {
        ReportFilters filters = requested == null ? ReportFilters.EMPTY : requested;
        return rows.stream().filter(row -> {
            boolean globalMatch = blank(filters.search())
                    || contains(row.getMetric(), filters.search())
                    || contains(row.getValue(), filters.search())
                    || contains(row.getDetail(), filters.search())
                    || contains(row.getSignal(), filters.search());
            return globalMatch
                    && (blank(filters.metric()) || contains(row.getMetric(), filters.metric()))
                    && (blank(filters.value()) || contains(row.getValue(), filters.value()))
                    && (blank(filters.detail()) || contains(row.getDetail(), filters.detail()))
                    && (blank(filters.signal()) || contains(row.getSignal(), filters.signal()));
        }).toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean contains(String value, String filter) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> findDefinition(String code, ReportFormat format) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, label, description, template_path, allowed_formats,
                       category, business_question, audience
                from reporting.report_definition
                where tenant_id = ? and code = ? and active = true
                """, TenantContext.get().tenantId(), code);
        if (rows.isEmpty()) throw new NotFoundException("Report definition not found");
        Map<String, Object> row = rows.getFirst();
        try {
            Object[] formats = (Object[]) ((java.sql.Array) row.get("allowed_formats")).getArray();
            boolean allowed = java.util.Arrays.stream(formats).map(String::valueOf).anyMatch(format.name()::equals);
            if (!allowed) throw new NotFoundException("Report format is not enabled for this report");
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Report formats could not be read", ex);
        }
        return row;
    }

    private List<ReportRow> rowsFor(String code) {
        UUID tenantId = TenantContext.get().tenantId();
        return switch (code) {
            case "tenant_summary" -> tenantSummary(tenantId);
            case "pipeline_snapshot" -> jdbc.query("""
                    select s.name as metric, coalesce(sum(o.amount), 0)::text as value,
                           count(o.id)::text || ' open opportunities' as detail,
                           case when count(o.id) = 0 then 'NO DEALS' else 'ACTIVE' end as signal
                    from crm.pipeline_stage s
                    left join sales.opportunity o on o.stage_id = s.id and o.tenant_id = s.tenant_id
                         and o.is_closed = false
                    where s.tenant_id = ? and s.deleted_at is null
                    group by s.name, s.sort_order order by s.sort_order
                    """, ReportService::mapReportRow, tenantId);
            case "forecast_commitment" -> jdbc.query("""
                    select coalesce(nullif(o.forecast_category, ''), 'UNCLASSIFIED') as metric,
                           coalesce(sum(o.amount), 0)::text as value,
                           count(*)::text || ' deals · weighted value ' ||
                           coalesce(round(sum(o.amount * coalesce(o.probability, s.probability, 0) / 100.0), 2), 0)::text as detail,
                           case when o.forecast_category is null or o.forecast_category = '' then 'CLASSIFY' else 'FORECAST' end as signal
                    from sales.opportunity o
                    left join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.stage_id
                    where o.tenant_id = ? and o.is_closed = false
                    group by coalesce(nullif(o.forecast_category, ''), 'UNCLASSIFIED'),
                             case when o.forecast_category is null or o.forecast_category = '' then 'CLASSIFY' else 'FORECAST' end
                    order by metric
                    """, ReportService::mapReportRow, tenantId);
            case "pipeline_aging_risk" -> jdbc.query("""
                    select s.name as metric, coalesce(sum(o.amount), 0)::text as value,
                           count(o.id)::text || ' open · ' ||
                           count(o.id) filter (where o.close_date < current_date or
                             o.stage_entered_at < now() - make_interval(days => coalesce(s.stalled_after_days, 30)))::text ||
                           ' stalled or overdue' as detail,
                           case when count(o.id) filter (where o.close_date < current_date or
                             o.stage_entered_at < now() - make_interval(days => coalesce(s.stalled_after_days, 30))) > 0
                             then 'AT RISK' else 'ON TRACK' end as signal
                    from crm.pipeline_stage s
                    left join sales.opportunity o on o.tenant_id = s.tenant_id and o.stage_id = s.id and o.is_closed = false
                    where s.tenant_id = ? and s.deleted_at is null
                    group by s.name, s.sort_order order by s.sort_order
                    """, ReportService::mapReportRow, tenantId);
            case "win_loss_analysis" -> jdbc.query("""
                    select case when is_won then 'WON' else 'LOST' end as metric,
                           coalesce(sum(amount), 0)::text as value,
                           count(*)::text || ' deals · average cycle ' ||
                           coalesce(round(avg(extract(epoch from (coalesce(closed_at, updated_at) - created_at)) / 86400.0)), 0)::text ||
                           ' days' as detail,
                           case when is_won then 'POSITIVE' else 'LEARN' end as signal
                    from sales.opportunity
                    where tenant_id = ? and is_closed = true
                    group by is_won order by is_won desc
                    """, ReportService::mapReportRow, tenantId);
            case "lead_conversion_funnel" -> jdbc.query("""
                    select coalesce(nullif(status, ''), 'UNCLASSIFIED') as metric,
                           count(*)::text as value,
                           count(*) filter (where converted_at is not null or converted_opportunity_id is not null)::text ||
                           ' converted · ' || round(100.0 * count(*) filter (where converted_at is not null or converted_opportunity_id is not null) /
                           nullif(count(*), 0), 1)::text || '% rate' as detail,
                           case when upper(coalesce(status, '')) in ('DISQUALIFIED','REJECTED') then 'REVIEW' else 'FUNNEL' end as signal
                    from crm.lead
                    where tenant_id = ? and deleted_at is null
                    group by coalesce(nullif(status, ''), 'UNCLASSIFIED'),
                             case when upper(coalesce(status, '')) in ('DISQUALIFIED','REJECTED') then 'REVIEW' else 'FUNNEL' end
                    order by count(*) desc, metric
                    """, ReportService::mapReportRow, tenantId);
            case "lead_source_conversion" -> jdbc.query("""
                    select coalesce(nullif(source, ''), 'UNKNOWN') as metric,
                           count(*)::text as value,
                           count(*) filter (where converted_at is not null or converted_opportunity_id is not null)::text ||
                           ' converted · ' || round(100.0 * count(*) filter (where converted_at is not null or converted_opportunity_id is not null) /
                           nullif(count(*), 0), 1)::text || '% rate' as detail,
                           case when source is null or source = '' then 'CLASSIFY' else 'MEASURED' end as signal
                    from crm.lead
                    where tenant_id = ? and deleted_at is null
                    group by coalesce(nullif(source, ''), 'UNKNOWN'),
                             case when source is null or source = '' then 'CLASSIFY' else 'MEASURED' end
                    order by count(*) desc, metric
                    """, ReportService::mapReportRow, tenantId);
            case "sales_activity_productivity" -> jdbc.query("""
                    select coalesce(nullif(activity_type, ''), 'OTHER') as metric,
                           count(*)::text as value,
                           count(*) filter (where completed_at is not null or upper(status) = 'COMPLETED')::text ||
                           ' completed · ' || coalesce(sum(duration_minutes), 0)::text || ' minutes logged' as detail,
                           case when count(*) filter (where completed_at is not null or upper(status) = 'COMPLETED') < count(*)
                             then 'FOLLOW UP' else 'COMPLETE' end as signal
                    from engagement.activity
                    where tenant_id = ? and deleted_at is null
                    group by coalesce(nullif(activity_type, ''), 'OTHER') order by count(*) desc, metric
                    """, ReportService::mapReportRow, tenantId);
            case "account_health_portfolio" -> jdbc.query("""
                    select coalesce(nullif(health_band, ''), 'NOT SCORED') as metric,
                           count(*)::text as value,
                           'revenue ' || coalesce(sum(annual_revenue), 0)::text || ' · average score ' ||
                           coalesce(round(avg(health_score), 1), 0)::text as detail,
                           case when upper(coalesce(health_band, '')) in ('AT RISK','CRITICAL','RED') then 'AT RISK'
                                when health_band is null or health_band = '' then 'SCORE' else 'HEALTHY' end as signal
                    from crm.account
                    where tenant_id = ? and deleted_at is null
                    group by coalesce(nullif(health_band, ''), 'NOT SCORED'),
                             case when upper(coalesce(health_band, '')) in ('AT RISK','CRITICAL','RED') then 'AT RISK'
                                  when health_band is null or health_band = '' then 'SCORE' else 'HEALTHY' end
                    order by count(*) desc, metric
                    """, ReportService::mapReportRow, tenantId);
            case "customer_service_sla" -> jdbc.query("""
                    select coalesce(nullif(priority, ''), 'UNPRIORITIZED') as metric,
                           count(*)::text as value,
                           count(*) filter (where upper(status) not in ('CLOSED','RESOLVED'))::text || ' open · ' ||
                           count(*) filter (where upper(status) not in ('CLOSED','RESOLVED') and
                             (first_response_due_at < now() or resolution_due_at < now()))::text || ' overdue milestones' as detail,
                           case when count(*) filter (where upper(status) not in ('CLOSED','RESOLVED') and
                             (first_response_due_at < now() or resolution_due_at < now())) > 0 then 'AT RISK' else 'ON TRACK' end as signal
                    from service.case_record
                    where tenant_id = ? and deleted_at is null
                    group by coalesce(nullif(priority, ''), 'UNPRIORITIZED') order by count(*) desc, metric
                    """, ReportService::mapReportRow, tenantId);
            case "quote_conversion_margin" -> jdbc.query("""
                    select coalesce(nullif(status, ''), 'UNCLASSIFIED') as metric,
                           coalesce(sum(grand_total), 0)::text as value,
                           count(*)::text || ' active versions · average margin ' ||
                           coalesce(round(avg(margin_pct), 1), 0)::text || '%' as detail,
                           case when upper(coalesce(status, '')) in ('REJECTED','EXPIRED') then 'LEARN'
                                when upper(coalesce(status, '')) = 'ACCEPTED' then 'POSITIVE' else 'OPEN' end as signal
                    from cpq.quote
                    where tenant_id = ? and deleted_at is null and is_active_version = true
                    group by coalesce(nullif(status, ''), 'UNCLASSIFIED'),
                             case when upper(coalesce(status, '')) in ('REJECTED','EXPIRED') then 'LEARN'
                                  when upper(coalesce(status, '')) = 'ACCEPTED' then 'POSITIVE' else 'OPEN' end
                    order by count(*) desc, metric
                    """, ReportService::mapReportRow, tenantId);
            case "campaign_roi" -> jdbc.query("""
                    select c.name as metric, coalesce(c.pipeline_influenced, 0)::text as value,
                           'budget ' || coalesce(c.budget_amount, 0)::text || ' · ' || count(cm.id)::text || ' members · ' ||
                           count(cm.id) filter (where cm.responded_at is not null)::text || ' responses · indicative return ' ||
                           case when coalesce(c.budget_amount, 0) = 0 then 'not computable'
                                else round(100.0 * (coalesce(c.pipeline_influenced, 0) - c.budget_amount) / c.budget_amount, 1)::text || '%' end as detail,
                           case when coalesce(c.budget_amount, 0) > 0 and coalesce(c.pipeline_influenced, 0) < c.budget_amount
                             then 'NEGATIVE' else 'MEASURED' end as signal
                    from marketing.campaign c
                    left join marketing.campaign_member cm on cm.tenant_id = c.tenant_id and cm.campaign_id = c.id
                    where c.tenant_id = ? and c.deleted_at is null
                    group by c.id, c.name, c.pipeline_influenced, c.budget_amount
                    order by coalesce(c.pipeline_influenced, 0) desc, c.name
                    """, ReportService::mapReportRow, tenantId);
            case "data_quality_exceptions" -> jdbc.query("""
                    with scope as (select ?::uuid as tenant_id), exceptions as (
                      select 'Accounts without an owner' metric, count(*) value,
                             'Ownership is required for routing, accountability and access.' detail
                        from crm.account where tenant_id = (select tenant_id from scope) and deleted_at is null and owner_id is null
                      union all
                      select 'Accounts without an industry', count(*),
                             'Industry is required for segmentation and comparable performance.'
                        from crm.account where tenant_id = (select tenant_id from scope) and deleted_at is null and nullif(industry, '') is null
                      union all
                      select 'Leads without an email', count(*),
                             'A missing email prevents digital follow-up and duplicate matching.'
                        from crm.lead where tenant_id = (select tenant_id from scope) and deleted_at is null and nullif(email, '') is null
                      union all
                      select 'Leads without an owner', count(*),
                             'Unowned demand can miss routing and response commitments.'
                        from crm.lead where tenant_id = (select tenant_id from scope) and deleted_at is null and owner_id is null
                      union all
                      select 'Open deals without a next step', count(*),
                             'Every open deal needs a clear seller action.'
                        from sales.opportunity where tenant_id = (select tenant_id from scope) and is_closed = false and nullif(next_step, '') is null
                      union all
                      select 'Open deals without a close date', count(*),
                             'A close date is required for forecast period placement.'
                        from sales.opportunity where tenant_id = (select tenant_id from scope) and is_closed = false and close_date is null
                      union all
                      select 'Contacts without an email', count(*),
                             'A missing email limits engagement and contact matching.'
                        from crm.contact where tenant_id = (select tenant_id from scope) and deleted_at is null and nullif(email, '') is null
                      union all
                      select 'Contacts without an account', count(*),
                             'Unlinked contacts cannot contribute to account-level relationship context.'
                        from crm.contact where tenant_id = (select tenant_id from scope) and deleted_at is null and account_id is null
                    )
                    select metric, value::text as value, detail,
                           case when value > 0 then 'ACTION REQUIRED' else 'CLEAR' end as signal
                    from exceptions order by value desc, metric
                    """, ReportService::mapReportRow, tenantId);
            case "quota_attainment" -> jdbc.query("""
                    with quota_actual as (
                      select q.id, q.subject_type, q.subject_label, q.version_no, q.target_amount,
                             q.currency_code, p.label period_label, p.start_date, p.end_date,
                             coalesce(sum(o.amount) filter (where o.is_closed and o.is_won = true), 0) actual_amount
                      from orgdata.quota q
                      join orgdata.fiscal_period p on p.tenant_id = q.tenant_id and p.id = q.fiscal_period_id
                      left join sales.opportunity o on o.tenant_id = q.tenant_id
                        and coalesce(o.closed_at::date, o.close_date) between p.start_date and p.end_date
                        and (
                          (q.subject_type = 'USER' and o.owner_id = q.subject_id)
                          or (q.subject_type = 'TERRITORY' and exists (
                            select 1 from orgdata.territory_assignment ta
                            where ta.tenant_id = q.tenant_id and ta.territory_id = q.subject_id
                              and ta.account_id = o.account_id))
                        )
                      where q.tenant_id = ? and q.measure = 'REVENUE' and q.is_current
                      group by q.id, q.subject_type, q.subject_label, q.version_no, q.target_amount,
                               q.currency_code, p.label, p.start_date, p.end_date
                    )
                    select subject_label || ' (' || subject_type || ')' metric,
                           case when target_amount = 0 then 'Not computable'
                                else round(100.0 * actual_amount / target_amount, 1)::text || '%' end value,
                           period_label || ' | target ' || target_amount::text || ' ' || currency_code ||
                           ' | actual ' || actual_amount::text || ' | gap ' ||
                           (target_amount - actual_amount)::text || ' | quota version ' || version_no::text detail,
                           case when subject_type = 'TEAM' then 'TEAM MAPPING REQUIRED'
                                when target_amount = 0 then 'TARGET REQUIRED'
                                when actual_amount >= target_amount then 'ATTAINED'
                                when actual_amount >= target_amount * 0.8 then 'WATCH'
                                else 'AT RISK' end signal
                    from quota_actual order by subject_type, subject_label
                    """, ReportService::mapReportRow, tenantId);
            case "forecast_accuracy_bias" -> jdbc.query("""
                    select coalesce(u.display_name, 'Unassigned') || ' | ' || p.label metric,
                           case when p.status <> 'CLOSED' then 'Pending outcome'
                                when s.submitted_amount = 0 then 'Not computable'
                                else greatest(0, round(100 - 100 * abs(actual.actual_amount - s.submitted_amount) /
                                     s.submitted_amount, 1))::text || '%' end value,
                           'submitted ' || s.submitted_amount::text || ' | actual ' || actual.actual_amount::text ||
                           ' | bias ' || (actual.actual_amount - s.submitted_amount)::text ||
                           ' | ' || s.forecast_category || ' | ' || s.status detail,
                           case when s.status = 'DRAFT' then 'DRAFT'
                                when p.status <> 'CLOSED' then 'PERIOD OPEN'
                                when s.submitted_amount = 0 then 'BASELINE REQUIRED'
                                when abs(actual.actual_amount - s.submitted_amount) <= s.submitted_amount * 0.05 then 'ON TARGET'
                                when actual.actual_amount > s.submitted_amount then 'UNDER FORECAST'
                                else 'OVER FORECAST' end signal
                    from forecasting.forecast_submission s
                    join forecasting.forecast_period p on p.tenant_id = s.tenant_id and p.id = s.period_id
                    left join identity.app_user u on u.tenant_id = s.tenant_id and u.id = s.owner_id
                    cross join lateral (
                      select coalesce(sum(o.amount), 0) actual_amount
                      from sales.opportunity o
                      where o.tenant_id = s.tenant_id and o.owner_id = s.owner_id
                        and o.is_closed and o.is_won = true
                        and coalesce(o.closed_at::date, o.close_date) between p.period_start and p.period_end
                    ) actual
                    where s.tenant_id = ?
                    order by p.period_start desc, coalesce(u.display_name, 'Unassigned'), s.forecast_category
                    """, ReportService::mapReportRow, tenantId);
            case "stage_conversion_velocity" -> jdbc.query("""
                    with occupancy as (
                      select h.*, lead(h.to_stage_id) over (
                               partition by h.opportunity_id order by h.entered_at, h.id) next_stage_id
                      from sales.stage_history h where h.tenant_id = ?
                    ), measured as (
                      select s.id stage_id, s.name stage_name, s.sort_order, s.stalled_after_days,
                             o.exited_at,
                             extract(epoch from (coalesce(o.exited_at, now()) - o.entered_at)) / 86400.0 elapsed_days,
                             case when ns.sort_order > s.sort_order then 1 else 0 end exited_forward
                      from occupancy o
                      join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.to_stage_id
                      left join crm.pipeline_stage ns on ns.tenant_id = o.tenant_id and ns.id = o.next_stage_id
                    )
                    select stage_name metric,
                           round(100.0 * sum(exited_forward) / nullif(count(*), 0), 1)::text || '%' value,
                           count(*)::text || ' entries | ' || count(*) filter (where exited_at is not null)::text ||
                           ' exits | ' || sum(exited_forward)::text || ' forward | average ' ||
                           round(avg(elapsed_days), 1)::text || ' days' detail,
                           case when avg(elapsed_days) > coalesce(stalled_after_days, 30) then 'VELOCITY RISK'
                                when sum(exited_forward) = 0 and count(*) filter (where exited_at is not null) > 0 then 'CONVERSION RISK'
                                when count(*) filter (where exited_at is null) = count(*) then 'IN PROGRESS'
                                else 'MEASURED' end signal
                    from measured
                    group by stage_id, stage_name, sort_order, stalled_after_days
                    order by sort_order
                    """, ReportService::mapReportRow, tenantId);
            case "renewal_arr_bridge" -> jdbc.query("""
                    with bounds as (
                      select coalesce(
                        (select p.start_date from orgdata.fiscal_period p
                          where p.tenant_id = ? and p.period_type = 'QUARTER'
                            and current_date between p.start_date and p.end_date limit 1),
                        date_trunc('quarter', current_date)::date) period_start,
                        coalesce(
                        (select p.end_date from orgdata.fiscal_period p
                          where p.tenant_id = ? and p.period_type = 'QUARTER'
                            and current_date between p.start_date and p.end_date limit 1),
                        (date_trunc('quarter', current_date) + interval '3 months - 1 day')::date) period_end
                    ), annualized as (
                      select s.*,
                        s.recurring_amount * case s.billing_frequency
                          when 'MONTHLY' then 12 when 'QUARTERLY' then 4
                          when 'SEMIANNUAL' then 2 else 1 end arr
                      from contracting.subscription s where s.tenant_id = ?
                    ), bridge as (
                      select 'Opening ARR' metric,
                        coalesce(sum(arr) filter (where start_date < b.period_start and end_date >= b.period_start
                          and status in ('ACTIVE','PENDING_RENEWAL')), 0) value,
                        count(*) filter (where start_date < b.period_start and end_date >= b.period_start
                          and status in ('ACTIVE','PENDING_RENEWAL')) records, 10 sort_order, 'BASELINE' signal
                      from annualized cross join bounds b
                      union all select 'New ARR',
                        coalesce(sum(arr) filter (where start_date between b.period_start and least(b.period_end, current_date)
                          and status in ('ACTIVE','PENDING_RENEWAL')), 0),
                        count(*) filter (where start_date between b.period_start and least(b.period_end, current_date)
                          and status in ('ACTIVE','PENDING_RENEWAL')), 20, 'GROWTH'
                      from annualized cross join bounds b
                      union all select 'Churned ARR',
                        -coalesce(sum(arr) filter (where end_date between b.period_start and b.period_end
                          and status in ('CANCELLED','EXPIRED')), 0),
                        count(*) filter (where end_date between b.period_start and b.period_end
                          and status in ('CANCELLED','EXPIRED')), 30, 'CHURN'
                      from annualized cross join bounds b
                      union all select 'ARR Due for Renewal in 90 Days',
                        coalesce(sum(arr) filter (where end_date between current_date and current_date + 90
                          and status in ('ACTIVE','PENDING_RENEWAL')), 0),
                        count(*) filter (where end_date between current_date and current_date + 90
                          and status in ('ACTIVE','PENDING_RENEWAL')), 40, 'RENEWAL WATCH'
                      from annualized cross join bounds b
                      union all select 'Closing ARR',
                        coalesce(sum(arr) filter (where start_date <= current_date and end_date >= current_date
                          and status in ('ACTIVE','PENDING_RENEWAL')), 0),
                        count(*) filter (where start_date <= current_date and end_date >= current_date
                          and status in ('ACTIVE','PENDING_RENEWAL')), 50, 'CURRENT'
                      from annualized cross join bounds b
                    )
                    select metric, value::text, records::text || ' subscriptions | annualized from billing frequency' detail,
                           case when signal in ('CHURN','RENEWAL WATCH') and (value <> 0 or records > 0) then 'AT RISK'
                                else signal end signal
                    from bridge order by sort_order
                    """, ReportService::mapReportRow, tenantId, tenantId, tenantId);
            case "pipeline_movement_waterfall" -> jdbc.query("""
                    with bounds as (
                      select date_trunc('quarter', current_date)::timestamptz period_start, now() period_end
                    ), before_state as (
                      select distinct on (h.opportunity_id) h.*
                      from sales.opportunity_state_history h cross join bounds b
                      where h.tenant_id = ? and h.observed_at <= b.period_start
                      order by h.opportunity_id, h.observed_at desc, h.id desc
                    ), after_state as (
                      select distinct on (h.opportunity_id) h.*
                      from sales.opportunity_state_history h cross join bounds b
                      where h.tenant_id = ? and h.observed_at <= b.period_end
                      order by h.opportunity_id, h.observed_at desc, h.id desc
                    ), compared as (
                      select coalesce(a.opportunity_id, b.opportunity_id) opportunity_id,
                        case
                          when b.opportunity_id is null or b.is_closed then 0 else b.amount end -
                        case when a.opportunity_id is null or a.is_closed then 0 else a.amount end delta,
                        case
                          when b.opportunity_id is not null and b.is_closed and (a.opportunity_id is null or not a.is_closed)
                            then case when b.is_won then 'Won' else 'Lost' end
                          when (a.opportunity_id is null or a.is_closed) and b.opportunity_id is not null and not b.is_closed then 'Added'
                          when a.opportunity_id is not null and not a.is_closed and (b.opportunity_id is null) then 'Removed'
                          when a.opportunity_id is not null and not a.is_closed and b.opportunity_id is not null and not b.is_closed
                               and b.amount > a.amount then 'Grown'
                          when a.opportunity_id is not null and not a.is_closed and b.opportunity_id is not null and not b.is_closed
                               and b.amount < a.amount then 'Shrunk'
                          else 'Unchanged' end category,
                        case when a.opportunity_id is not null and not a.is_closed then a.amount else 0 end before_amount,
                        case when b.opportunity_id is not null and not b.is_closed then b.amount else 0 end after_amount
                      from before_state a full outer join after_state b using (opportunity_id)
                    ), categories(category, sort_order) as (
                      values ('Added',20),('Grown',30),('Shrunk',40),('Won',50),('Lost',60),('Removed',70),('Unchanged',80)
                    ), bucketed as (
                      select c.category metric, coalesce(sum(x.delta), 0) value, count(x.opportunity_id) records, c.sort_order
                      from categories c left join compared x on x.category = c.category group by c.category, c.sort_order
                    ), output as (
                      select 'Opening Pipeline' metric, coalesce(sum(before_amount),0) value,
                             count(*) filter (where before_amount <> 0) records, 10 sort_order from compared
                      union all select metric, value, records, sort_order from bucketed
                      union all select 'Closing Pipeline', coalesce(sum(after_amount),0),
                             count(*) filter (where after_amount <> 0), 90 from compared
                    )
                    select metric, value::text,
                           records::text || ' opportunities | current fiscal quarter comparison' detail,
                           case when metric in ('Shrunk','Lost','Removed') and value <> 0 then 'NEGATIVE MOVEMENT'
                                when metric in ('Opening Pipeline','Closing Pipeline') then 'RECONCILED'
                                else 'MOVEMENT' end signal
                    from output order by sort_order
                    """, ReportService::mapReportRow, tenantId, tenantId);
            case "account_whitespace" -> jdbc.query("""
                    select a.name metric,
                           missing.product_count::text || ' products' value,
                           case when missing.product_count = 0 then 'No catalogue whitespace detected.'
                                else missing.product_names end detail,
                           case when missing.product_count = 0 then 'FULL COVERAGE' else 'CROSS SELL' end signal
                    from crm.account a
                    cross join lateral (
                      select count(*) product_count,
                             coalesce(string_agg(p.name, ', ' order by p.product_family, p.name), '') product_names
                      from cpq.product p
                      where p.tenant_id = a.tenant_id and p.is_active and p.deleted_at is null
                        and not exists (
                          select 1 from contracting.subscription s
                          where s.tenant_id = a.tenant_id and s.account_id = a.id
                            and s.product_code = p.code and s.status in ('ACTIVE','PENDING_RENEWAL'))
                        and not exists (
                          select 1 from sales.opportunity o
                          join sales.opportunity_line l on l.tenant_id = o.tenant_id and l.opportunity_id = o.id
                          where o.tenant_id = a.tenant_id and o.account_id = a.id and not o.is_closed
                            and l.product_code = p.code)
                    ) missing
                    where a.tenant_id = ? and a.deleted_at is null
                    order by missing.product_count desc, a.name
                    """, ReportService::mapReportRow, tenantId);
            case "customer_360_brief" -> jdbc.query("""
                    select a.name metric,
                           'ARR ' || commercial.arr::text value,
                           'health ' || coalesce(health.band, 'Not scored') || ' (' || coalesce(health.score::text, 'n/a') || ')' ||
                           ' | open pipeline ' || commercial.pipeline::text ||
                           ' | ' || relationships.contacts::text || ' contacts' ||
                           ' | ' || service.open_cases::text || ' open cases' ||
                           ' | next renewal ' || coalesce(commercial.next_renewal::text, 'not scheduled') detail,
                           case when service.overdue_cases > 0 then 'SERVICE RISK'
                                when upper(coalesce(health.band, '')) in ('AT RISK','CRITICAL','RED') then 'HEALTH RISK'
                                when commercial.next_renewal between current_date and current_date + 90 then 'RENEWAL WATCH'
                                else 'CURRENT' end signal
                    from crm.account a
                    cross join lateral (
                      select coalesce(sum(s.recurring_amount * case s.billing_frequency
                               when 'MONTHLY' then 12 when 'QUARTERLY' then 4
                               when 'SEMIANNUAL' then 2 else 1 end)
                               filter (where s.status in ('ACTIVE','PENDING_RENEWAL')), 0) arr,
                             min(s.end_date) filter (where s.status in ('ACTIVE','PENDING_RENEWAL') and s.end_date >= current_date) next_renewal,
                             coalesce((select sum(o.amount) from sales.opportunity o
                               where o.tenant_id = a.tenant_id and o.account_id = a.id and not o.is_closed), 0) pipeline
                      from contracting.subscription s where s.tenant_id = a.tenant_id and s.account_id = a.id
                    ) commercial
                    cross join lateral (
                      select count(*) contacts from crm.contact c
                      where c.tenant_id = a.tenant_id and c.account_id = a.id and c.deleted_at is null
                    ) relationships
                    cross join lateral (
                      select count(*) filter (where upper(c.status) not in ('CLOSED','RESOLVED')) open_cases,
                             count(*) filter (where upper(c.status) not in ('CLOSED','RESOLVED') and
                               (c.first_response_due_at < now() or c.resolution_due_at < now())) overdue_cases
                      from service.case_record c where c.tenant_id = a.tenant_id and c.account_id = a.id and c.deleted_at is null
                    ) service
                    left join lateral (
                      select h.band, h.score from crm.account_health_snapshot h
                      where h.tenant_id = a.tenant_id and h.account_id = a.id
                      order by h.computed_at desc limit 1
                    ) health on true
                    where a.tenant_id = ? and a.deleted_at is null
                    order by commercial.arr desc, a.name
                    """, ReportService::mapReportRow, tenantId);
            case "discount_approval_governance" -> jdbc.query("""
                    select q.quote_number || ' | ' || q.name metric,
                           coalesce(q.discount_total, sum(l.discount_amount), 0)::text value,
                           'quote value ' || coalesce(q.grand_total, 0)::text ||
                           ' | quote discount ' || coalesce(q.quote_discount_pct, 0)::text || '%' ||
                           ' | average line discount ' || coalesce(round(avg(l.discount_pct), 1), 0)::text || '%' ||
                           ' | margin ' || coalesce(q.margin_pct, 0)::text || '%' ||
                           ' | approval ' || coalesce(q.approval_status, 'NOT REQUESTED') detail,
                           case when coalesce(q.quote_discount_pct, 0) > 0 and
                                      upper(coalesce(q.approval_status, '')) not in ('APPROVED','NOT_REQUIRED') then 'APPROVAL GAP'
                                when coalesce(q.margin_pct, 100) < 20 then 'MARGIN RISK'
                                else 'GOVERNED' end signal
                    from cpq.quote q
                    left join cpq.quote_line l on l.tenant_id = q.tenant_id and l.quote_id = q.id
                    where q.tenant_id = ? and q.deleted_at is null and q.is_active_version
                    group by q.id, q.quote_number, q.name, q.discount_total, q.grand_total,
                             q.quote_discount_pct, q.margin_pct, q.approval_status
                    order by coalesce(q.discount_total, sum(l.discount_amount), 0) desc, q.quote_number
                    """, ReportService::mapReportRow, tenantId);
            default -> throw new NotFoundException("Report query is not implemented");
        };
    }

    private List<ReportRow> tenantSummary(UUID tenantId) {
        Map<String, Object> counts = jdbc.queryForMap("""
                select
                  (select count(*) from crm.account where tenant_id = ? and deleted_at is null) as accounts,
                  (select count(*) from crm.lead where tenant_id = ? and deleted_at is null) as leads,
                  (select count(*) from sales.opportunity where tenant_id = ? and is_closed = false) as open_deals,
                  (select coalesce(sum(amount),0) from sales.opportunity where tenant_id = ? and is_closed = false) as open_pipeline
                """, tenantId, tenantId, tenantId, tenantId);
        return List.of(
                new ReportRow("Accounts", String.valueOf(counts.get("accounts")), "Active customer and prospect organizations", "PORTFOLIO"),
                new ReportRow("Leads", String.valueOf(counts.get("leads")), "Active demand records", "DEMAND"),
                new ReportRow("Open Deals", String.valueOf(counts.get("open_deals")), "Unclosed revenue opportunities", "PIPELINE"),
                new ReportRow("Open Pipeline", ((BigDecimal) counts.get("open_pipeline")).toPlainString(), "Total unclosed opportunity value", "REVENUE")
        );
    }

    private static ReportRow mapReportRow(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new ReportRow(rs.getString("metric"), rs.getString("value"),
                rs.getString("detail"), rs.getString("signal"));
    }

    private byte[] render(Map<String, Object> definition, ReportSpec spec,
                          ReportFormat format, List<ReportRow> rows, String datasetFingerprint) {
        Resource resource = resources.getResource("classpath:" + definition.get("template_path"));
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            JasperReport report = JasperCompileManager.compileReport(resource.getInputStream());
            Map<String, Object> params = new HashMap<>();
            params.put("REPORT_TITLE", String.valueOf(definition.get("label")));
            params.put("REPORT_DESCRIPTION", String.valueOf(definition.get("description")));
            params.put("REPORT_CATEGORY", String.valueOf(definition.get("category")).replace('_', ' '));
            params.put("BUSINESS_QUESTION", String.valueOf(definition.get("business_question")));
            params.put("REPORT_AUDIENCE", String.join(" · ", sqlArray(definition.get("audience"))));
            params.put("TENANT_NAME", jdbc.queryForObject(
                    "select name from platform.tenant where id = ?", String.class, TenantContext.get().tenantId()));
            params.put("GENERATED_AT", OffsetDateTime.now().toString());
            params.put("DIMENSION_LABEL", spec.dimensionLabel());
            params.put("VALUE_LABEL", spec.valueLabel());
            params.put("DETAIL_LABEL", spec.detailLabel());
            params.put("DATASET_FINGERPRINT", datasetFingerprint);
            params.put("REPORT_ROW_COUNT", rows.size());
            JasperPrint print = JasperFillManager.fillReport(report, params, new JRBeanCollectionDataSource(rows));
            switch (format) {
                case PDF -> {
                    return JasperExportManager.exportReportToPdf(print);
                }
                case XLSX -> {
                    JRXlsxExporter exporter = new JRXlsxExporter();
                    exporter.setExporterInput(new SimpleExporterInput(print));
                    exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
                    SimpleXlsxReportConfiguration config = new SimpleXlsxReportConfiguration();
                    config.setDetectCellType(true);
                    config.setOnePagePerSheet(false);
                    exporter.setConfiguration(config);
                    exporter.exportReport();
                    return out.toByteArray();
                }
                case DOCX -> {
                    JRDocxExporter exporter = new JRDocxExporter();
                    exporter.setExporterInput(new SimpleExporterInput(print));
                    exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(out));
                    exporter.exportReport();
                    return out.toByteArray();
                }
            }
            throw new IllegalStateException("Unsupported report format: " + format);
        } catch (IOException | JRException ex) {
            throw new IllegalStateException("Jasper report export failed", ex);
        }
    }

    private static List<String> sqlArray(Object value) {
        try {
            if (value instanceof java.sql.Array array) {
                return java.util.Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
            }
            if (value instanceof Object[] array) {
                return java.util.Arrays.stream(array).map(String::valueOf).toList();
            }
            return List.of();
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Report audience could not be read", ex);
        }
    }
}
