package com.axiom.reporting;

import com.axiom.auth.CrmRole;
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
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                                      List<String> allowedFormats, boolean active) {}
    public record FilePayload(byte[] bytes, String contentType, String filename) {}
    public static class ReportRow {
        private final String metric;
        private final String value;
        private final String detail;
        public ReportRow(String metric, String value, String detail) {
            this.metric = metric;
            this.value = value;
            this.detail = detail;
        }
        public String getMetric() { return metric; }
        public String getValue() { return value; }
        public String getDetail() { return detail; }
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
                select id, code, label, description, allowed_formats, active
                from reporting.report_definition
                where tenant_id = ? and active = true
                order by label
                """, (rs, i) -> {
            Object[] raw = (Object[]) rs.getArray("allowed_formats").getArray();
            return new ReportDefinitionRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("label"),
                    rs.getString("description"),
                    java.util.Arrays.stream(raw).map(String::valueOf).toList(),
                    rs.getBoolean("active")
            );
        }, TenantContext.get().tenantId());
    }

    @Transactional
    public FilePayload export(String code, ReportFormat format) {
        CrmRole.requireExport(TenantContext.get().role());
        Map<String, Object> definition = findDefinition(code, format);
        List<ReportRow> rows = rowsFor(code);
        byte[] bytes = render((String) definition.get("template_path"), (String) definition.get("label"), format, rows);
        jdbc.update("""
                insert into reporting.report_run(tenant_id, report_definition_id, format, status, row_count, generated_by)
                values (?, ?, ?, 'GENERATED', ?, ?)
                """, TenantContext.get().tenantId(), definition.get("id"), format.name(), rows.size(), TenantContext.get().userId());
        String fileCode = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return new FilePayload(bytes, format.contentType, fileCode + "." + format.extension);
    }

    private Map<String, Object> findDefinition(String code, ReportFormat format) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, label, template_path, allowed_formats
                from reporting.report_definition
                where tenant_id = ? and code = ? and active = true
                """, TenantContext.get().tenantId(), code);
        if (rows.isEmpty()) throw new NotFoundException("Report definition not found");
        Map<String, Object> row = rows.getFirst();
        try {
            Object[] formats = (Object[]) ((java.sql.Array) row.get("allowed_formats")).getArray();
            boolean allowed = java.util.Arrays.stream(formats).map(String::valueOf).anyMatch(format.name()::equals);
            if (!allowed) throw new NotFoundException("Report format is not enabled for this report");
        } catch (Exception ex) {
            throw new IllegalStateException("Report formats could not be read", ex);
        }
        return row;
    }

    private List<ReportRow> rowsFor(String code) {
        UUID tenantId = TenantContext.get().tenantId();
        if ("pipeline_snapshot".equals(code)) {
            return jdbc.query("""
                    select s.name as metric, coalesce(sum(o.amount), 0)::text as value,
                           count(o.id)::text || ' open opportunities' as detail
                    from crm.pipeline_stage s
                    left join sales.opportunity o on o.stage_id = s.id and o.tenant_id = s.tenant_id and o.is_closed = false
                    where s.tenant_id = ? and s.deleted_at is null
                    group by s.name, s.sort_order order by s.sort_order
                    """, (rs, i) -> new ReportRow(rs.getString("metric"), rs.getString("value"), rs.getString("detail")), tenantId);
        }

        Map<String, Object> counts = jdbc.queryForMap("""
                select
                  (select count(*) from crm.account where tenant_id = ? and deleted_at is null) as accounts,
                  (select count(*) from crm.lead where tenant_id = ? and deleted_at is null) as leads,
                  (select count(*) from sales.opportunity where tenant_id = ? and is_closed = false) as open_deals,
                  (select coalesce(sum(amount),0) from sales.opportunity where tenant_id = ? and is_closed = false) as open_pipeline
                """, tenantId, tenantId, tenantId, tenantId);
        return List.of(
                new ReportRow("Accounts", String.valueOf(counts.get("accounts")), "Active account master records"),
                new ReportRow("Leads", String.valueOf(counts.get("leads")), "Active lead transaction records"),
                new ReportRow("Open Deals", String.valueOf(counts.get("open_deals")), "Unclosed opportunities"),
                new ReportRow("Open Pipeline", ((BigDecimal) counts.get("open_pipeline")).toPlainString(), "Total unclosed opportunity value")
        );
    }

    private byte[] render(String templatePath, String title, ReportFormat format, List<ReportRow> rows) {
        Resource resource = resources.getResource("classpath:" + templatePath);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            JasperReport report = JasperCompileManager.compileReport(resource.getInputStream());
            Map<String, Object> params = new HashMap<>();
            params.put("REPORT_TITLE", "Axiom CRM - " + title);
            params.put("GENERATED_AT", OffsetDateTime.now().toString());
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
}
