package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fair-use throttling with visible telemetry — the whole of FR-AUT-014.
 *
 * <h2>What FR-AUT-014 actually forbids</h2>
 * "The platform must not impose a fixed numeric limit on automation rules per
 * object or per tenant. Resource protection must be by fair-use throttling with
 * visible telemetry, never by an arbitrary count cap." The competitive analysis
 * (§6) is the reason: a rule cap is a limit the customer discovers at the worst
 * possible moment, cannot measure in advance, and cannot do anything about
 * except delete working automation.
 *
 * <p>So this module bounds <em>executions per window</em>, which is a real
 * resource, and publishes the number. A tenant may define ten thousand rules;
 * all ten thousand are evaluated. What is bounded is how much work the engine
 * will do per minute, and {@link #telemetry()} states the ceiling, the current
 * usage, the recent history and — explicitly — that there is no rule cap, so an
 * administrator planning a large model can see that before they build it.
 */
@Service
public class ThrottleService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    @Autowired
    public ThrottleService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Policy(int windowSeconds, int maxExecutions, int maxCascadeDepth) {}

    public record Decision(boolean allowed, int used, int limit, int windowSeconds, String message) {}

    public record WindowSample(Instant windowStart, int executions, int throttled) {}

    /**
     * @param ruleCountLimit always null, and present in the payload precisely so
     *                       the UI can state "no cap" from data rather than from a
     *                       hard-coded string
     */
    public record Telemetry(Policy policy, int rulesDefined, int rulesActive,
                            int executionsInCurrentWindow, int throttledInCurrentWindow,
                            Integer ruleCountLimit, String resourceProtection,
                            List<WindowSample> recentWindows) {}

    @Transactional(readOnly = true)
    public Policy policy() {
        List<Policy> rows = jdbc.query("""
                select window_seconds, max_executions, max_cascade_depth
                from automation.throttle_policy where tenant_id = ?
                """, (rs, i) -> new Policy(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                TenantContext.get().tenantId());
        return rows.isEmpty() ? new Policy(60, 2000, 8) : rows.getFirst();
    }

    /**
     * Count one execution against the current window.
     *
     * <p>The upsert is the counter: concurrent executions contend on one row and
     * PostgreSQL serializes them, so the number is the number rather than an
     * approximation assembled from application-side state.
     */
    @Transactional
    public Decision acquire() {
        Policy policy = policy();
        Instant windowStart = windowStart(policy.windowSeconds());
        Integer used = jdbc.queryForObject("""
                insert into automation.throttle_window (tenant_id, window_start, executions)
                values (?, ?, 1)
                on conflict (tenant_id, window_start)
                do update set executions = automation.throttle_window.executions + 1
                returning executions
                """, Integer.class, TenantContext.get().tenantId(),
                java.sql.Timestamp.from(windowStart));
        int count = used == null ? 1 : used;
        if (count > policy.maxExecutions()) {
            jdbc.update("""
                    update automation.throttle_window set throttled = throttled + 1
                    where tenant_id = ? and window_start = ?
                    """, TenantContext.get().tenantId(), java.sql.Timestamp.from(windowStart));
            String message = "Automation is being fair-use throttled: " + count + " executions in the last "
                    + policy.windowSeconds() + "s exceeds the tenant ceiling of " + policy.maxExecutions()
                    + ". This is a rate limit, not a rule limit — no rule was disabled and none will be.";
            audit.record("AUTOMATION_THROTTLED", "AUTOMATION", null, message,
                    Map.of("used", count, "limit", policy.maxExecutions(),
                            "windowSeconds", policy.windowSeconds()));
            return new Decision(false, count, policy.maxExecutions(), policy.windowSeconds(), message);
        }
        return new Decision(true, count, policy.maxExecutions(), policy.windowSeconds(),
                count + " of " + policy.maxExecutions() + " executions used in this "
                        + policy.windowSeconds() + "s window.");
    }

    @Transactional(readOnly = true)
    public Telemetry telemetry() {
        AutomationAccess.requireRead();
        UUID tenantId = TenantContext.get().tenantId();
        Policy policy = policy();
        Instant windowStart = windowStart(policy.windowSeconds());

        Integer defined = jdbc.queryForObject(
                "select count(*) from automation.rule_definition where tenant_id = ?",
                Integer.class, tenantId);
        Integer active = jdbc.queryForObject(
                "select count(*) from automation.rule_definition where tenant_id = ? and status = 'ACTIVE'",
                Integer.class, tenantId);

        List<WindowSample> recent = jdbc.query("""
                select window_start, executions, throttled from automation.throttle_window
                where tenant_id = ? order by window_start desc limit 30
                """, (rs, i) -> new WindowSample(rs.getTimestamp(1).toInstant(),
                        rs.getInt(2), rs.getInt(3)), tenantId);

        WindowSample current = recent.stream()
                .filter(w -> w.windowStart().equals(windowStart)).findFirst()
                .orElse(new WindowSample(windowStart, 0, 0));

        return new Telemetry(policy, defined == null ? 0 : defined, active == null ? 0 : active,
                current.executions(), current.throttled(), null,
                "Fair-use rate limiting per " + policy.windowSeconds() + "s window. "
                        + "There is no limit on the number of rules per object or per tenant.",
                recent);
    }

    @Transactional
    public Policy updatePolicy(Policy policy) {
        AutomationAccess.requireAdmin("change the automation throttle policy");
        jdbc.update("""
                insert into automation.throttle_policy
                  (tenant_id, window_seconds, max_executions, max_cascade_depth, updated_by, updated_at)
                values (?, ?, ?, ?, ?, now())
                on conflict (tenant_id) do update
                  set window_seconds = excluded.window_seconds,
                      max_executions = excluded.max_executions,
                      max_cascade_depth = excluded.max_cascade_depth,
                      updated_by = excluded.updated_by, updated_at = now()
                """, TenantContext.get().tenantId(), policy.windowSeconds(), policy.maxExecutions(),
                policy.maxCascadeDepth(), TenantContext.get().userId());
        audit.record("AUTOMATION_THROTTLE_POLICY", "AUTOMATION", null,
                "Updated the automation fair-use policy",
                Map.of("windowSeconds", policy.windowSeconds(),
                        "maxExecutions", policy.maxExecutions(),
                        "maxCascadeDepth", policy.maxCascadeDepth()));
        return policy();
    }

    static Instant windowStart(int windowSeconds) {
        long seconds = Math.max(1, windowSeconds);
        long epoch = Instant.now().getEpochSecond();
        return Instant.ofEpochSecond(epoch - Math.floorMod(epoch, seconds));
    }
}
