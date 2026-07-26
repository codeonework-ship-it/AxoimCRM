package com.axiom.analytics;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Product layer over the governed reporting engine: dashboards, collaboration and delivery. */
@Service
public class ReportStudioService {

    public record Widget(UUID id, String title, String visualizationType, String reportCode,
                         int x, int y, int width, int height, Map<String, Object> configuration,
                         int sortOrder) {}
    public record Dashboard(UUID id, String code, String name, String description, String status,
                            String layoutMode, String audience, int refreshIntervalMinutes,
                            UUID ownerId, Instant lastRefreshedAt, List<Widget> widgets) {}
    public record DashboardRequest(String code, String name, String description, String layoutMode,
                                   String audience, Integer refreshIntervalMinutes) {}
    public record WidgetRequest(UUID id, String title, String visualizationType, String reportCode,
                                Integer x, Integer y, Integer width, Integer height,
                                Map<String, Object> configuration, Integer sortOrder) {}
    public record Share(UUID id, String targetType, String targetCode, String principalType,
                        String principalKey, String permission, Instant createdAt) {}
    public record ShareRequest(String targetType, String targetCode, String principalType,
                               String principalKey, String permission) {}
    public record Comment(UUID id, String targetType, String targetCode, String body,
                          UUID createdBy, Instant createdAt, Instant resolvedAt) {}
    public record CommentRequest(String targetType, String targetCode, String body) {}
    public record DeliveryPolicy(UUID id, String targetType, String targetCode, String name,
                                 String artifactFormat, String frequency, List<String> recipients,
                                 String thresholdMetricCode, String thresholdOperator,
                                 BigDecimal thresholdValue, boolean enabled, Instant nextRunAt,
                                 BigDecimal lastEvaluatedValue, Instant lastTriggeredAt,
                                 String deliveryState) {}
    public record DeliveryRequest(String targetType, String targetCode, String name,
                                  String artifactFormat, String frequency, List<String> recipients,
                                  String thresholdMetricCode, String thresholdOperator,
                                  BigDecimal thresholdValue, Instant nextRunAt) {}
    public record DeliveryEvaluation(UUID policyId, boolean triggered, BigDecimal evaluatedValue,
                                     Instant nextEvaluationAt, String state, String reason) {}
    public record EmbedView(UUID id, String targetType, String targetCode, String embedCode,
                            List<String> allowedOrigins, boolean requireLogin, boolean active,
                            String relativeUrl) {}
    public record EmbedRequest(String targetType, String targetCode, String embedCode,
                               List<String> allowedOrigins) {}
    public record PerformanceSummary(long executions, long timeouts, long truncated,
                                     long p95Ms, long maximumMs, BigDecimal averageRows,
                                     String assessment) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final KpiCalculationService kpis;

    public ReportStudioService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit,
                               KpiCalculationService kpis) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.kpis = kpis;
    }

    @Transactional(readOnly = true)
    public List<Dashboard> dashboards() {
        return jdbc.query("""
                select id, dashboard_code, name, description, status, layout_mode, audience,
                       refresh_interval_minutes, owner_id, last_refreshed_at
                 from reporting.analytics_dashboard
                 where tenant_id = ? and status <> 'ARCHIVED' and (
                       ? or owner_id=? or audience='TENANT' or exists (
                         select 1 from analytics.report_share s
                          where s.tenant_id=analytics_dashboard.tenant_id
                            and s.dashboard_id=analytics_dashboard.id and s.revoked_at is null
                            and ((s.principal_type='USER' and s.principal_key in (?, ?))
                              or (s.principal_type='ROLE' and s.principal_key=?)
                              or s.principal_type='TENANT')))
                 order by name
                """, (rs, row) -> new Dashboard(rs.getObject("id", UUID.class), rs.getString("dashboard_code"),
                rs.getString("name"), rs.getString("description"), rs.getString("status"),
                rs.getString("layout_mode"), rs.getString("audience"), rs.getInt("refresh_interval_minutes"),
                rs.getObject("owner_id", UUID.class), instant(rs, "last_refreshed_at"),
                widgets(rs.getObject("id", UUID.class))), TenantContext.get().tenantId(),
                CrmRole.current(TenantContext.get().role()).platform(), user(), user().toString(),
                TenantContext.get().email(), TenantContext.get().role());
    }

    @Transactional
    public Dashboard saveDashboard(DashboardRequest request) {
        requireWrite();
        String code = code(request == null ? null : request.code());
        String name = required(request.name(), "Dashboard name");
        String layout = oneOf(request.layoutMode(), "GRID", List.of("GRID", "FREEFORM"));
        String audience = oneOf(request.audience(), "PRIVATE", List.of("PRIVATE", "SHARED", "TENANT"));
        int refresh = request.refreshIntervalMinutes() == null ? 60 : request.refreshIntervalMinutes();
        if (refresh < 5 || refresh > 10080) throw new IllegalArgumentException("Refresh interval must be between 5 and 10080 minutes");
        jdbc.update("""
                insert into reporting.analytics_dashboard
                  (tenant_id, dashboard_code, name, description, status, owner_id,
                   refresh_interval_minutes, layout_mode, audience)
                values (?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                on conflict (tenant_id, dashboard_code) do update set
                  name = excluded.name, description = excluded.description,
                  refresh_interval_minutes = excluded.refresh_interval_minutes,
                  layout_mode = excluded.layout_mode, audience = excluded.audience, updated_at = now()
                """, tenant(), code, name, clean(request.description()), user(), refresh, layout, audience);
        Dashboard saved = dashboard(code);
        audit.record("DASHBOARD_SAVED", "ANALYTICS_DASHBOARD", saved.id(),
                "Saved dashboard " + saved.name(), Map.of("code", code, "layout", layout));
        return saved;
    }

    @Transactional
    public Widget saveWidget(String dashboardCode, WidgetRequest request) {
        requireWrite();
        Dashboard dashboard = dashboard(dashboardCode);
        String reportCode = code(request.reportCode());
        UUID reportId = reportId(reportCode);
        String visual = oneOf(request.visualizationType(), "TABLE",
                List.of("KPI","BAR","LINE","AREA","DONUT","FUNNEL","TABLE","PIVOT","SUMMARY"));
        int x = bounded(request.x(), 0, 0, 11, "Widget column");
        int y = bounded(request.y(), 0, 0, 1000, "Widget row");
        int width = bounded(request.width(), 4, 1, 12, "Widget width");
        int height = bounded(request.height(), 3, 1, 12, "Widget height");
        if (x + width > 12) throw new IllegalArgumentException("Widget column plus width cannot exceed the 12-column dashboard grid");
        int sort = bounded(request.sortOrder(), 10, 0, 10000, "Widget order");
        String config = writeJson(request.configuration() == null ? Map.of() : request.configuration());
        UUID id = request.id() == null ? UUID.randomUUID() : request.id();
        int changed = jdbc.update("""
                insert into reporting.dashboard_widget
                  (id, tenant_id, dashboard_id, title, visualization_type, source_module,
                   metric_code, metric_value, sort_order, report_view_id, layout_x, layout_y,
                   layout_width, layout_height, configuration)
                values (?, ?, ?, ?, ?, 'ANALYTICS', ?, 0, ?, ?, ?, ?, ?, ?, ?::jsonb)
                on conflict (id) do update set
                  title = excluded.title, visualization_type = excluded.visualization_type,
                  report_view_id = excluded.report_view_id, metric_code = excluded.metric_code,
                  sort_order = excluded.sort_order, layout_x = excluded.layout_x,
                  layout_y = excluded.layout_y, layout_width = excluded.layout_width,
                  layout_height = excluded.layout_height, configuration = excluded.configuration
                where dashboard_widget.tenant_id = excluded.tenant_id
                  and dashboard_widget.dashboard_id = excluded.dashboard_id
                """, id, tenant(), dashboard.id(), required(request.title(), "Widget title"), visual,
                reportCode, sort, reportId, x, y, width, height, config);
        if (changed == 0) throw new NotFoundException("Widget is not part of this dashboard");
        audit.record("DASHBOARD_WIDGET_SAVED", "DASHBOARD_WIDGET", id,
                "Saved " + visual.toLowerCase(Locale.ROOT) + " widget", Map.of("dashboard", dashboardCode, "report", reportCode));
        return widgets(dashboard.id()).stream().filter(widget -> widget.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void archiveDashboard(String dashboardCode) {
        requireWrite();
        Dashboard dashboard = dashboard(dashboardCode);
        jdbc.update("update reporting.analytics_dashboard set status = 'ARCHIVED', updated_at = now() where tenant_id = ? and id = ?",
                tenant(), dashboard.id());
        audit.record("DASHBOARD_ARCHIVED", "ANALYTICS_DASHBOARD", dashboard.id(),
                "Archived dashboard " + dashboard.name(), Map.of("code", dashboard.code()));
    }

    @Transactional(readOnly = true)
    public List<Share> shares() {
        return jdbc.query("""
                select s.id, case when s.report_view_id is not null then 'REPORT' else 'DASHBOARD' end target_type,
                       coalesce(r.code, d.dashboard_code) target_code, s.principal_type, s.principal_key,
                       s.permission, s.created_at
                  from analytics.report_share s
                  left join analytics.report_view r on r.tenant_id=s.tenant_id and r.id=s.report_view_id
                  left join reporting.analytics_dashboard d on d.tenant_id=s.tenant_id and d.id=s.dashboard_id
                 where s.tenant_id=? and s.revoked_at is null order by s.created_at desc
                """, (rs, row) -> new Share(rs.getObject("id", UUID.class), rs.getString("target_type"),
                rs.getString("target_code"), rs.getString("principal_type"), rs.getString("principal_key"),
                rs.getString("permission"), rs.getTimestamp("created_at").toInstant()), tenant());
    }

    @Transactional
    public Share share(ShareRequest request) {
        requireWrite();
        String target = oneOf(request.targetType(), null, List.of("REPORT","DASHBOARD"));
        String principal = oneOf(request.principalType(), null, List.of("USER","ROLE","TENANT"));
        String permission = oneOf(request.permission(), "VIEW", List.of("VIEW","EDIT"));
        UUID reportId = "REPORT".equals(target) ? reportId(code(request.targetCode())) : null;
        UUID dashboardId = "DASHBOARD".equals(target) ? dashboard(code(request.targetCode())).id() : null;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into analytics.report_share
                  (id, tenant_id, report_view_id, dashboard_id, principal_type, principal_key, permission, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenant(), reportId, dashboardId, principal,
                required(request.principalKey(), "Share recipient"), permission, user());
        audit.record("REPORT_SHARED", "REPORT_SHARE", id, "Shared reporting content",
                Map.of("targetType", target, "targetCode", request.targetCode(), "permission", permission));
        return shares().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void revokeShare(UUID id) {
        requireWrite();
        int changed = jdbc.update("update analytics.report_share set revoked_at=now() where tenant_id=? and id=? and revoked_at is null", tenant(), id);
        if (changed == 0) throw new NotFoundException("Active report share not found");
        audit.record("REPORT_SHARE_REVOKED", "REPORT_SHARE", id, "Revoked reporting share", Map.of());
    }

    @Transactional(readOnly = true)
    public List<Comment> comments(String targetType, String targetCode) {
        Target target = target(targetType, targetCode);
        return jdbc.query("""
                select id, body, created_by, created_at, resolved_at from analytics.report_comment
                 where tenant_id=? and report_view_id is not distinct from ? and dashboard_id is not distinct from ?
                 order by created_at desc
                """, (rs, row) -> new Comment(rs.getObject("id", UUID.class), target.type(), target.code(),
                rs.getString("body"), rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                instant(rs, "resolved_at")), tenant(), target.reportId(), target.dashboardId());
    }

    @Transactional
    public Comment comment(CommentRequest request) {
        requireWrite();
        Target target = target(request.targetType(), request.targetCode());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into analytics.report_comment(id,tenant_id,report_view_id,dashboard_id,body,created_by) values (?,?,?,?,?,?)",
                id, tenant(), target.reportId(), target.dashboardId(), required(request.body(), "Comment"), user());
        return comments(target.type(), target.code()).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<DeliveryPolicy> deliveries() {
        return jdbc.query("""
                select * from analytics.delivery_policy where tenant_id=? order by name
                """, (rs, row) -> new DeliveryPolicy(rs.getObject("id", UUID.class), rs.getString("target_type"),
                rs.getString("target_code"), rs.getString("name"), rs.getString("artifact_format"),
                rs.getString("frequency"), strings(rs.getArray("recipients")), rs.getString("threshold_metric_code"),
                rs.getString("threshold_operator"), rs.getBigDecimal("threshold_value"), rs.getBoolean("enabled"),
                rs.getTimestamp("next_run_at").toInstant(), rs.getBigDecimal("last_evaluated_value"),
                instant(rs, "last_triggered_at"), rs.getString("delivery_state")), tenant());
    }

    @Transactional
    public DeliveryPolicy schedule(DeliveryRequest request) {
        requireWrite();
        Target target = target(request.targetType(), request.targetCode());
        String frequency = oneOf(request.frequency(), "WEEKLY", List.of("DAILY","WEEKLY","MONTHLY","THRESHOLD"));
        String format = oneOf(request.artifactFormat(), "PDF", List.of("PDF","XLSX","DOCX","LINK"));
        if (request.recipients() == null || request.recipients().isEmpty()
                || request.recipients().stream().anyMatch(value -> value == null || !value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            throw new IllegalArgumentException("Provide at least one valid recipient email address");
        }
        String metric = "THRESHOLD".equals(frequency) ? required(request.thresholdMetricCode(), "Threshold metric") : null;
        String operator = "THRESHOLD".equals(frequency)
                ? oneOf(request.thresholdOperator(), null, List.of("GT","GTE","LT","LTE","EQ")) : null;
        BigDecimal value = "THRESHOLD".equals(frequency) ? request.thresholdValue() : null;
        if ("THRESHOLD".equals(frequency) && value == null) throw new IllegalArgumentException("Threshold value is required");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into analytics.delivery_policy
                  (id,tenant_id,target_type,target_code,name,artifact_format,frequency,recipients,
                   threshold_metric_code,threshold_operator,threshold_value,next_run_at,created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, tenant(), target.type(), target.code(), required(request.name(), "Delivery name"),
                format, frequency, request.recipients().toArray(String[]::new), metric, operator, value,
                Timestamp.from(request.nextRunAt() == null ? Instant.now() : request.nextRunAt()), user());
        audit.record("REPORT_DELIVERY_SCHEDULED", "REPORT_DELIVERY", id, "Scheduled reporting delivery",
                Map.of("target", target.code(), "frequency", frequency, "adapterState", "PENDING_ADAPTER"));
        return deliveries().stream().filter(policy -> policy.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Evaluate due policies. Thresholds always call the governed KPI service; a
     * second formula implementation here would eventually disagree with the dashboard.
     * Crossing semantics prevent an alert storm while a metric remains out of bounds.
     */
    @Transactional
    public List<DeliveryEvaluation> evaluateDeliveries() {
        requireWrite();
        Instant now = Instant.now();
        return deliveries().stream().filter(DeliveryPolicy::enabled)
                .filter(policy -> !policy.nextRunAt().isAfter(now))
                .map(policy -> evaluate(policy, now)).toList();
    }

    private DeliveryEvaluation evaluate(DeliveryPolicy policy, Instant now) {
        BigDecimal current = null;
        boolean triggered;
        String reason;
        if ("THRESHOLD".equals(policy.frequency())) {
            KpiCalculationService.KpiValue metric = kpis.compute(policy.thresholdMetricCode(),
                    new KpiCalculationService.KpiScope(null, null, null));
            if (!metric.computable() || metric.value() == null) {
                Instant next = now.plus(15, ChronoUnit.MINUTES);
                jdbc.update("""
                        update analytics.delivery_policy set next_run_at=?, delivery_state='PENDING_ADAPTER',
                          updated_at=now() where tenant_id=? and id=?
                        """, Timestamp.from(next), tenant(), policy.id());
                return new DeliveryEvaluation(policy.id(), false, null, next, "PENDING_ADAPTER",
                        "Metric is not computable: " + String.join(", ", metric.missingInputs()));
            }
            current = metric.value();
            boolean nowMatches = thresholdMatches(current, policy.thresholdOperator(), policy.thresholdValue());
            boolean previouslyMatched = policy.lastEvaluatedValue() != null
                    && thresholdMatches(policy.lastEvaluatedValue(), policy.thresholdOperator(), policy.thresholdValue());
            triggered = nowMatches && !previouslyMatched;
            reason = triggered ? policy.thresholdMetricCode() + " crossed " + policy.thresholdOperator()
                    + " " + policy.thresholdValue() : "Threshold did not cross its configured boundary";
        } else {
            triggered = true;
            reason = "Scheduled " + policy.frequency().toLowerCase(Locale.ROOT) + " delivery is due";
        }
        Instant next = advance(policy.nextRunAt(), policy.frequency());
        String state = triggered ? "QUEUED" : "PENDING_ADAPTER";
        jdbc.update("""
                update analytics.delivery_policy
                   set last_evaluated_value=?, last_triggered_at=case when ? then now() else last_triggered_at end,
                       next_run_at=?, delivery_state=?, updated_at=now()
                 where tenant_id=? and id=?
                """, current, triggered, Timestamp.from(next), state, tenant(), policy.id());
        if (triggered) audit.record("REPORT_DELIVERY_QUEUED", "REPORT_DELIVERY", policy.id(),
                "Queued governed reporting delivery", Map.of("target", policy.targetCode(), "reason", reason));
        return new DeliveryEvaluation(policy.id(), triggered, current, next, state, reason);
    }

    static boolean thresholdMatches(BigDecimal value, String operator, BigDecimal boundary) {
        int comparison = value.compareTo(boundary);
        return switch (operator) {
            case "GT" -> comparison > 0;
            case "GTE" -> comparison >= 0;
            case "LT" -> comparison < 0;
            case "LTE" -> comparison <= 0;
            case "EQ" -> comparison == 0;
            default -> throw new IllegalArgumentException("Unsupported threshold operator " + operator);
        };
    }

    static Instant advance(Instant from, String frequency) {
        return switch (frequency) {
            case "DAILY" -> from.plus(1, ChronoUnit.DAYS);
            case "WEEKLY" -> from.plus(7, ChronoUnit.DAYS);
            case "MONTHLY" -> from.plus(30, ChronoUnit.DAYS);
            case "THRESHOLD" -> from.plus(15, ChronoUnit.MINUTES);
            default -> throw new IllegalArgumentException("Unsupported delivery frequency " + frequency);
        };
    }

    @Transactional(readOnly = true)
    public List<EmbedView> embeds() {
        return jdbc.query("""
                select id,target_type,target_code,embed_code,allowed_origins,require_login,active
                  from analytics.embed_view where tenant_id=? and revoked_at is null order by embed_code
                """, (rs, row) -> new EmbedView(rs.getObject("id", UUID.class), rs.getString("target_type"),
                rs.getString("target_code"), rs.getString("embed_code"), strings(rs.getArray("allowed_origins")),
                rs.getBoolean("require_login"), rs.getBoolean("active"),
                "/embedded/analytics/" + rs.getString("embed_code")), tenant());
    }

    @Transactional
    public EmbedView embed(EmbedRequest request) {
        requireWrite();
        Target target = target(request.targetType(), request.targetCode());
        String embedCode = code(request.embedCode()).replace('_', '-');
        List<String> origins = request.allowedOrigins() == null ? List.of() : request.allowedOrigins();
        if (origins.stream().anyMatch(origin -> !origin.matches("^https?://[^/\\s]+(?::\\d+)?$"))) {
            throw new IllegalArgumentException("Allowed origins must be an exact http(s) origin without a path");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into analytics.embed_view
                  (id,tenant_id,target_type,target_code,embed_code,allowed_origins,require_login,created_by)
                values (?,?,?,?,?,?,true,?)
                """, id, tenant(), target.type(), target.code(), embedCode, origins.toArray(String[]::new), user());
        audit.record("ANALYTICS_EMBED_CREATED", "EMBED_VIEW", id, "Created authenticated analytics embed",
                Map.of("target", target.code(), "allowedOrigins", origins));
        return embeds().stream().filter(view -> view.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public PerformanceSummary performance() {
        Map<String, Object> row = jdbc.queryForMap("""
                select count(*) executions,
                       count(*) filter (where status='TIMEOUT') timeouts,
                       count(*) filter (where truncated) truncated,
                       coalesce(percentile_cont(0.95) within group (order by elapsed_ms),0)::bigint p95_ms,
                       coalesce(max(elapsed_ms),0)::bigint maximum_ms,
                       coalesce(avg(row_count),0) average_rows
                  from analytics.query_execution
                 where tenant_id=? and executed_at >= now() - interval '30 days'
                """, tenant());
        long p95 = ((Number) row.get("p95_ms")).longValue();
        long timeouts = ((Number) row.get("timeouts")).longValue();
        String assessment = timeouts > 0 ? "Needs attention: narrow timed-out reports or add a projection index."
                : p95 > 3000 ? "Watch: the 95th percentile is above the 3-second interactive target."
                : "Healthy: analytical queries are within the interactive performance target.";
        return new PerformanceSummary(((Number) row.get("executions")).longValue(), timeouts,
                ((Number) row.get("truncated")).longValue(), p95,
                ((Number) row.get("maximum_ms")).longValue(), (BigDecimal) row.get("average_rows"), assessment);
    }

    private Dashboard dashboard(String code) {
        return dashboards().stream().filter(value -> value.code().equalsIgnoreCase(code)).findFirst()
                .orElseThrow(() -> new NotFoundException("Dashboard not found"));
    }

    private List<Widget> widgets(UUID dashboardId) {
        return jdbc.query("""
                select w.id,w.title,w.visualization_type,r.code report_code,w.layout_x,w.layout_y,
                       w.layout_width,w.layout_height,w.configuration,w.sort_order
                  from reporting.dashboard_widget w
                  left join analytics.report_view r on r.tenant_id=w.tenant_id and r.id=w.report_view_id
                 where w.tenant_id=? and w.dashboard_id=? order by w.layout_y,w.layout_x,w.sort_order
                """, (rs, row) -> new Widget(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getString("visualization_type"), rs.getString("report_code"), rs.getInt("layout_x"),
                rs.getInt("layout_y"), rs.getInt("layout_width"), rs.getInt("layout_height"),
                readJson(rs.getString("configuration")), rs.getInt("sort_order")), tenant(), dashboardId);
    }

    private UUID reportId(String reportCode) {
        List<UUID> rows = jdbc.query("select id from analytics.report_view where tenant_id=? and code=?",
                (rs, row) -> rs.getObject(1, UUID.class), tenant(), reportCode);
        if (rows.isEmpty()) throw new NotFoundException("Saved report not found");
        return rows.get(0);
    }

    private record Target(String type, String code, UUID reportId, UUID dashboardId) {}
    private Target target(String type, String code) {
        String target = oneOf(type, null, List.of("REPORT","DASHBOARD"));
        String normalized = code(code);
        return "REPORT".equals(target)
                ? new Target(target, normalized, reportId(normalized), null)
                : new Target(target, normalized, null, dashboard(normalized).id());
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String code(String value) {
        String code = required(value, "Code").trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!code.matches("^[a-z][a-z0-9_-]{0,63}$")) throw new IllegalArgumentException("Code must start with a letter and use lower-case letters, digits, hyphens or underscores");
        return code;
    }
    private static String oneOf(String value, String fallback, List<String> allowed) {
        String selected = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (selected == null || !allowed.contains(selected)) throw new IllegalArgumentException("Value must be one of " + String.join(", ", allowed));
        return selected;
    }
    private static int bounded(Integer value, int fallback, int min, int max, String label) {
        int selected = value == null ? fallback : value;
        if (selected < min || selected > max) throw new IllegalArgumentException(label + " must be between " + min + " and " + max);
        return selected;
    }
    private static Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
    private static List<String> strings(Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
    private Map<String, Object> readJson(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Dashboard widget configuration is invalid", ex); }
    }
    private String writeJson(Map<String, Object> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Widget configuration is not valid JSON", ex); }
    }
    private static UUID tenant() { return TenantContext.get().tenantId(); }
    private static UUID user() { return TenantContext.get().userId(); }
    private static void requireWrite() { CrmRole.requireWrite(TenantContext.get().role()); }
}
