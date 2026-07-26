package com.axiom.reporting;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportSubscriptionService {
    private final JdbcTemplate jdbc;
    private final ReportService reports;
    private final AuditService audit;

    public ReportSubscriptionService(JdbcTemplate jdbc, ReportService reports, AuditService audit) {
        this.jdbc = jdbc;
        this.reports = reports;
        this.audit = audit;
    }

    public record CreateRequest(String reportCode, String name, ReportService.ReportFormat format,
                                String frequency, List<String> recipients, Instant nextRunAt) {}
    public record Subscription(UUID id, String reportCode, String name, String format, String frequency,
                               List<String> recipients, boolean enabled, Instant nextRunAt, Instant lastRunAt) {}
    public record RunResult(UUID subscriptionId, String status, String filename, Instant nextRunAt) {}

    @Transactional
    public Subscription create(CreateRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        if (request == null || request.name() == null || request.name().isBlank())
            throw new IllegalArgumentException("Subscription name is required.");
        if (request.recipients() == null || request.recipients().isEmpty()
                || request.recipients().stream().anyMatch(value -> value == null || !value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")))
            throw new IllegalArgumentException("Provide at least one valid recipient email address.");
        String frequency = request.frequency() == null ? "WEEKLY" : request.frequency().toUpperCase();
        if (!List.of("DAILY", "WEEKLY", "MONTHLY").contains(frequency))
            throw new IllegalArgumentException("Frequency must be DAILY, WEEKLY or MONTHLY.");
        Map<String, Object> definition = jdbc.queryForList("""
                select id from reporting.report_definition where tenant_id = ? and code = ? and active = true
                """, TenantContext.get().tenantId(), request.reportCode()).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Active report definition not found"));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into reporting.report_subscription
                  (id, tenant_id, report_definition_id, name, format, frequency, recipients,
                   next_run_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, TenantContext.get().tenantId(), definition.get("id"), request.name().trim(),
                request.format().name(), frequency, request.recipients().toArray(String[]::new),
                Timestamp.from(request.nextRunAt() == null ? Instant.now() : request.nextRunAt()),
                TenantContext.get().userId());
        audit.record("REPORT_SUBSCRIPTION_CREATED", "REPORT_SUBSCRIPTION", id,
                "Created report subscription " + request.name(), Map.of("reportCode", request.reportCode()));
        return find(id);
    }

    @Transactional(readOnly = true)
    public List<Subscription> list() {
        return jdbc.query("""
                select s.id, d.code, s.name, s.format, s.frequency, s.recipients,
                       s.enabled, s.next_run_at, s.last_run_at
                from reporting.report_subscription s join reporting.report_definition d
                  on d.tenant_id = s.tenant_id and d.id = s.report_definition_id
                where s.tenant_id = ? order by s.name
                """, (rs, i) -> new Subscription(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("format"), rs.getString("frequency"),
                strings(rs.getArray("recipients")), rs.getBoolean("enabled"),
                rs.getTimestamp("next_run_at").toInstant(),
                rs.getTimestamp("last_run_at") == null ? null : rs.getTimestamp("last_run_at").toInstant()),
                TenantContext.get().tenantId());
    }

    @Transactional
    public List<RunResult> runDue() {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        return list().stream().filter(Subscription::enabled).filter(s -> !s.nextRunAt().isAfter(Instant.now()))
                .map(this::generate).toList();
    }

    private RunResult generate(Subscription subscription) {
        try {
            ReportService.FilePayload file = reports.export(subscription.reportCode(),
                    ReportService.ReportFormat.valueOf(subscription.format()));
            Instant next = advance(subscription.nextRunAt(), subscription.frequency());
            jdbc.update("""
                    insert into reporting.report_subscription_run(tenant_id, subscription_id, status, filename)
                    values (?, ?, 'GENERATED', ?)
                    """, TenantContext.get().tenantId(), subscription.id(), file.filename());
            jdbc.update("""
                    update reporting.report_subscription set last_run_at = now(), next_run_at = ?
                    where tenant_id = ? and id = ?
                    """, Timestamp.from(next), TenantContext.get().tenantId(), subscription.id());
            audit.record("REPORT_SUBSCRIPTION_GENERATED", "REPORT_SUBSCRIPTION", subscription.id(),
                    "Generated scheduled report " + file.filename(), Map.of("nextRunAt", next));
            return new RunResult(subscription.id(), "GENERATED", file.filename(), next);
        } catch (RuntimeException ex) {
            jdbc.update("""
                    insert into reporting.report_subscription_run
                      (tenant_id, subscription_id, status, error_message) values (?, ?, 'FAILED', ?)
                    """, TenantContext.get().tenantId(), subscription.id(), ex.getMessage());
            return new RunResult(subscription.id(), "FAILED", null, subscription.nextRunAt());
        }
    }

    private Subscription find(UUID id) {
        return list().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Report subscription not found"));
    }

    static Instant advance(Instant from, String frequency) {
        return switch (frequency) {
            case "DAILY" -> from.plus(1, ChronoUnit.DAYS);
            case "WEEKLY" -> from.plus(7, ChronoUnit.DAYS);
            case "MONTHLY" -> from.plus(30, ChronoUnit.DAYS);
            default -> throw new IllegalArgumentException("Unsupported frequency " + frequency);
        };
    }

    private static List<String> strings(Array array) throws java.sql.SQLException {
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
