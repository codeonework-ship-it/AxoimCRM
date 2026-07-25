package com.axiom.alerts;

import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AlertService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;

    public AlertService(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record EmailAlertRow(UUID id, String name, String subject, String bodyHtml,
                                List<String> to, List<String> cc, List<String> bcc,
                                boolean attachmentOptional, boolean active, OffsetDateTime createdAt) {}
    public record ReportAlertRow(UUID id, UUID reportDefinitionId, String reportLabel, String name,
                                 String subject, String bodyHtml, List<String> to, List<String> cc,
                                 List<String> bcc, List<String> formats, boolean active,
                                 OffsetDateTime createdAt) {}
    public record EmailAlertRequest(@NotBlank String name, @NotBlank String subject,
                                    @NotBlank String bodyHtml, @NotEmpty List<String> to,
                                    List<String> cc, List<String> bcc, boolean attachmentOptional) {}
    public record ReportAlertRequest(@NotBlank String name, @NotBlank String subject,
                                     @NotBlank String bodyHtml, @NotEmpty List<String> to,
                                     List<String> cc, List<String> bcc, @NotEmpty List<String> formats,
                                     UUID reportDefinitionId) {}
    public record DispatchResult(UUID dispatchId, String status, String message) {}

    @Transactional(readOnly = true)
    public List<EmailAlertRow> emailAlerts() {
        return jdbc.query("""
                select id, name, subject, body_html, to_list, cc_list, bcc_list,
                       attachment_optional, active, created_at
                from engagement.email_alert_config
                where tenant_id = ? order by created_at desc
                """, (rs, i) -> new EmailAlertRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("subject"),
                rs.getString("body_html"),
                array(rs.getArray("to_list")),
                array(rs.getArray("cc_list")),
                array(rs.getArray("bcc_list")),
                rs.getBoolean("attachment_optional"),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), TenantContext.get().tenantId());
    }

    @Transactional
    public EmailAlertRow createEmailAlert(EmailAlertRequest request) {
        ensureTenantAdmin();
        validateEmails(request.to(), "to");
        validateEmails(request.cc(), "cc");
        validateEmails(request.bcc(), "bcc");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into engagement.email_alert_config
                  (id, tenant_id, name, subject, body_html, to_list, cc_list, bcc_list, attachment_optional, created_by)
                values (?, ?, ?, ?, ?, ?::text[], ?::text[], ?::text[], ?, ?)
                """, id, TenantContext.get().tenantId(), request.name().trim(), request.subject().trim(),
                request.bodyHtml(), request.to().toArray(String[]::new),
                safe(request.cc()).toArray(String[]::new), safe(request.bcc()).toArray(String[]::new),
                request.attachmentOptional(), TenantContext.get().userId());
        return emailAlerts().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Email alert was not found"));
    }

    @Transactional
    public DispatchResult sendEmailAlert(UUID id) {
        EmailAlertRow alert = emailAlerts().stream().filter(row -> row.id().equals(id) && row.active()).findFirst()
                .orElseThrow(() -> new NotFoundException("Active email alert not found"));
        UUID dispatchId = UUID.randomUUID();
        jdbc.update("""
                insert into engagement.email_alert_dispatch
                  (id, tenant_id, config_id, status, attachment_name)
                values (?, ?, ?, 'QUEUED', ?)
                """, dispatchId, TenantContext.get().tenantId(), id,
                alert.attachmentOptional() ? null : "required-attachment-placeholder");
        outbox.write("EMAIL_ALERT", dispatchId, "EmailAlertQueued", Map.of(
                "configId", id.toString(),
                "subject", alert.subject(),
                "to", alert.to()
        ));
        return new DispatchResult(dispatchId, "QUEUED", "Email alert queued for the configured mail service");
    }

    @Transactional(readOnly = true)
    public List<ReportAlertRow> reportAlerts() {
        return jdbc.query("""
                select c.id, c.report_definition_id, rd.label as report_label, c.name,
                       c.subject, c.body_html, c.to_list, c.cc_list, c.bcc_list,
                       c.formats, c.active, c.created_at
                from engagement.report_alert_config c
                join reporting.report_definition rd on rd.id = c.report_definition_id and rd.tenant_id = c.tenant_id
                where c.tenant_id = ? order by c.created_at desc
                """, (rs, i) -> new ReportAlertRow(
                rs.getObject("id", UUID.class),
                rs.getObject("report_definition_id", UUID.class),
                rs.getString("report_label"),
                rs.getString("name"),
                rs.getString("subject"),
                rs.getString("body_html"),
                array(rs.getArray("to_list")),
                array(rs.getArray("cc_list")),
                array(rs.getArray("bcc_list")),
                array(rs.getArray("formats")),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), TenantContext.get().tenantId());
    }

    @Transactional
    public ReportAlertRow createReportAlert(ReportAlertRequest request) {
        ensureTenantAdmin();
        validateEmails(request.to(), "to");
        validateEmails(request.cc(), "cc");
        validateEmails(request.bcc(), "bcc");
        List<String> formats = safe(request.formats()).stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .filter(value -> List.of("PDF", "XLSX", "DOCX").contains(value))
                .distinct()
                .toList();
        if (formats.isEmpty()) throw new IllegalArgumentException("At least one valid report format is required");
        UUID reportDefinitionId = request.reportDefinitionId();
        if (reportDefinitionId == null) {
            reportDefinitionId = jdbc.queryForObject("""
                    select id from reporting.report_definition
                    where tenant_id = ? and code = 'tenant_summary' and active = true limit 1
                    """, UUID.class, TenantContext.get().tenantId());
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into engagement.report_alert_config
                  (id, tenant_id, report_definition_id, name, subject, body_html, to_list, cc_list, bcc_list, formats, created_by)
                values (?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?::text[], ?::text[], ?)
                """, id, TenantContext.get().tenantId(), reportDefinitionId, request.name().trim(),
                request.subject().trim(), request.bodyHtml(), request.to().toArray(String[]::new),
                safe(request.cc()).toArray(String[]::new), safe(request.bcc()).toArray(String[]::new),
                formats.toArray(String[]::new), TenantContext.get().userId());
        return reportAlerts().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Report alert was not found"));
    }

    @Transactional
    public DispatchResult sendReportAlert(UUID id) {
        ReportAlertRow alert = reportAlerts().stream().filter(row -> row.id().equals(id) && row.active()).findFirst()
                .orElseThrow(() -> new NotFoundException("Active report alert not found"));
        UUID dispatchId = UUID.randomUUID();
        jdbc.update("""
                insert into engagement.report_alert_dispatch
                  (id, tenant_id, config_id, status, generated_formats)
                values (?, ?, ?, 'QUEUED', ?::text[])
                """, dispatchId, TenantContext.get().tenantId(), id, alert.formats().toArray(String[]::new));
        outbox.write("REPORT_ALERT", dispatchId, "ReportAlertQueued", Map.of(
                "configId", id.toString(),
                "reportDefinitionId", alert.reportDefinitionId().toString(),
                "formats", alert.formats(),
                "to", alert.to()
        ));
        return new DispatchResult(dispatchId, "QUEUED", "Report alert queued with report attachments");
    }

    private void ensureTenantAdmin() {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (!(role == CrmRole.SUPER_ADMIN || role == CrmRole.TENANT_ADMIN || role == CrmRole.OPERATIONS)) {
            throw new com.axiom.common.ForbiddenException("Alert configuration requires Super Admin, Tenant Admin or Operations");
        }
    }

    private void validateEmails(List<String> values, String field) {
        for (String value : safe(values)) {
            if (!EMAIL.matcher(value.trim()).matches()) {
                throw new IllegalArgumentException("Invalid " + field + " email address: " + value);
            }
        }
    }

    private List<String> safe(List<String> values) {
        return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    private List<String> array(Array array) throws SQLException {
        if (array == null) return List.of();
        Object value = array.getArray();
        if (value instanceof String[] strings) return Arrays.asList(strings);
        if (value instanceof Object[] objects) return Arrays.stream(objects).map(String::valueOf).toList();
        return List.of();
    }
}
