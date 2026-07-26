package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The per-run execution log of FR-AUT-011: trigger, entry-condition outcome,
 * each step, each action result, total duration — retained configurably and
 * filterable by record.
 *
 * <h2>Only live runs are written here</h2>
 * A dry run produces the same trace object and this class never sees it. That is
 * what makes FR-AUT-010's "none of them occurring" checkable rather than
 * asserted: the simulation path has no writer.
 */
@Service
public class ExecutionLogService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    @Autowired
    public ExecutionLogService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    public record ExecutionRow(UUID id, UUID ruleId, String ruleCode, int ruleVersionNo,
                               String triggerType, String triggerEvent, String objectType, UUID recordId,
                               boolean entryConditionMet, String entryConditionDetail, String status,
                               String haltedReason, int actionsExecuted, int cascadeDepth,
                               int durationMs, String correlationId, Instant startedAt, Instant completedAt,
                               List<StepRow> steps) {}

    public record StepRow(int stepNo, String stepKey, String stepType, String label, String outcome,
                          Map<String, Object> detail, int durationMs, Instant occurredAt) {}

    // ------------------------------------------------------------------ write

    /** @return the execution id, so a caller can link straight to the trace. */
    @Transactional
    public UUID record(RuleModel.ExecutionTrace trace) {
        UUID tenantId = TenantContext.get().tenantId();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into automation.rule_execution
                  (id, tenant_id, rule_id, rule_code, rule_version_no, trigger_type, trigger_event,
                   object_type, record_id, entry_condition_met, entry_condition_detail, status,
                   halted_reason, actions_executed, cascade_depth, duration_ms, correlation_id,
                   started_at, completed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                """, id, tenantId, trace.ruleId(), trace.ruleCode(), trace.versionNo(),
                trace.triggerType(), trace.triggerEvent(), trace.objectType(), trace.recordId(),
                trace.entryConditionMet(), trace.entryConditionDetail(), trace.status(),
                trace.haltedReason(), trace.actionsExecuted(), trace.cascadeDepth(),
                (int) trace.durationMs(), MDC.get("correlationId"));

        for (RuleModel.StepTrace step : trace.steps()) {
            jdbc.update("""
                    insert into automation.rule_execution_step
                      (id, tenant_id, execution_id, step_no, step_key, step_type, label, outcome,
                       detail, duration_ms)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, UUID.randomUUID(), tenantId, id, step.stepNo(), step.stepKey(),
                    step.stepType(), step.label(), step.outcome(), writeJson(step.detail()),
                    (int) step.durationMs());
        }

        // A halted cascade is a control event, not merely a log line: someone has
        // to be able to find it without knowing which rule to look under.
        if ("HALTED".equals(trace.status())) {
            audit.record("AUTOMATION_CASCADE_HALTED", trace.objectType(), trace.recordId(),
                    trace.haltedReason(),
                    Map.of("ruleCode", trace.ruleCode(), "executionId", id.toString(),
                            "cascadeDepth", trace.cascadeDepth()));
        }
        return id;
    }

    /** A run that never started because the tenant is being fair-use throttled. */
    @Transactional
    public UUID recordThrottled(RuleDefinitionService.ActiveRule rule, String objectType, UUID recordId,
                                String event, String message, int cascadeDepth) {
        return record(new RuleModel.ExecutionTrace(rule.id(), rule.ruleCode(), rule.name(),
                rule.versionNo(), objectType, recordId, rule.triggerType(), event,
                false, message, "THROTTLED", null, 0, cascadeDepth, 0,
                List.of(new RuleModel.StepTrace(1, "throttle", "GUARD", "Fair-use throttle",
                        "THROTTLED", Map.of("message", message), 0))));
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public List<ExecutionRow> list(UUID ruleId, String objectType, UUID recordId, String status, int limit) {
        AutomationAccess.requireRead();
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        StringBuilder filter = new StringBuilder();
        if (ruleId != null) { filter.append(" and rule_id = ?"); args.add(ruleId); }
        if (objectType != null && !objectType.isBlank()) {
            filter.append(" and object_type = ?");
            args.add(objectType.toUpperCase(java.util.Locale.ROOT));
        }
        if (recordId != null) { filter.append(" and record_id = ?"); args.add(recordId); }
        if (status != null && !status.isBlank()) {
            filter.append(" and status = ?");
            args.add(status.toUpperCase(java.util.Locale.ROOT));
        }
        int capped = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                select * from automation.rule_execution
                where tenant_id = ?""" + filter + """

                order by started_at desc limit """ + capped,
                (rs, i) -> mapExecution(rs, List.of()), args.toArray());
    }

    @Transactional(readOnly = true)
    public ExecutionRow get(UUID executionId) {
        AutomationAccess.requireRead();
        List<StepRow> steps = jdbc.query("""
                select step_no, step_key, step_type, label, outcome, detail::text, duration_ms, occurred_at
                from automation.rule_execution_step
                where tenant_id = ? and execution_id = ?
                order by step_no
                """, (rs, i) -> new StepRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), readJson(rs.getString(6)), rs.getInt(7),
                        rs.getTimestamp(8).toInstant()),
                TenantContext.get().tenantId(), executionId);

        List<ExecutionRow> rows = jdbc.query("""
                select * from automation.rule_execution where tenant_id = ? and id = ?
                """, (rs, i) -> mapExecution(rs, steps), TenantContext.get().tenantId(), executionId);
        if (rows.isEmpty()) throw new NotFoundException("No automation execution with that id");
        return rows.getFirst();
    }

    // ------------------------------------------------------------------ retention

    public record RetentionPolicy(int retainDays) {}

    @Transactional(readOnly = true)
    public RetentionPolicy retention() {
        AutomationAccess.requireRead();
        List<Integer> rows = jdbc.query(
                "select retain_days from automation.execution_retention_policy where tenant_id = ?",
                (rs, i) -> rs.getInt(1), TenantContext.get().tenantId());
        return new RetentionPolicy(rows.isEmpty() ? 90 : rows.getFirst());
    }

    @Transactional
    public RetentionPolicy setRetention(int retainDays) {
        AutomationAccess.requireAdmin("change automation log retention");
        if (retainDays < 1 || retainDays > 3650) {
            throw new IllegalArgumentException("Retention must be between 1 and 3650 days.");
        }
        jdbc.update("""
                insert into automation.execution_retention_policy (tenant_id, retain_days, updated_by, updated_at)
                values (?, ?, ?, now())
                on conflict (tenant_id) do update
                  set retain_days = excluded.retain_days, updated_by = excluded.updated_by, updated_at = now()
                """, TenantContext.get().tenantId(), retainDays, TenantContext.get().userId());
        audit.record("AUTOMATION_RETENTION_SET", "AUTOMATION", null,
                "Automation execution logs are retained for " + retainDays + " days",
                Map.of("retainDays", retainDays));
        return retention();
    }

    /** Applies the configured retention. Step rows go with their header by cascade. */
    @Transactional
    public int purge() {
        AutomationAccess.requireAdmin("purge automation execution logs");
        int days = retention().retainDays();
        int deleted = jdbc.update("""
                delete from automation.rule_execution
                where tenant_id = ? and started_at < now() - (? || ' days')::interval
                """, TenantContext.get().tenantId(), String.valueOf(days));
        audit.record("AUTOMATION_LOG_PURGED", "AUTOMATION", null,
                "Purged " + deleted + " automation executions older than " + days + " days",
                Map.of("deleted", deleted, "retainDays", days));
        return deleted;
    }

    // ------------------------------------------------------------------ plumbing

    private ExecutionRow mapExecution(java.sql.ResultSet rs, List<StepRow> steps)
            throws java.sql.SQLException {
        return new ExecutionRow(rs.getObject("id", UUID.class), rs.getObject("rule_id", UUID.class),
                rs.getString("rule_code"), rs.getInt("rule_version_no"), rs.getString("trigger_type"),
                rs.getString("trigger_event"), rs.getString("object_type"),
                rs.getObject("record_id", UUID.class), rs.getBoolean("entry_condition_met"),
                rs.getString("entry_condition_detail"), rs.getString("status"),
                rs.getString("halted_reason"), rs.getInt("actions_executed"), rs.getInt("cascade_depth"),
                rs.getInt("duration_ms"), rs.getString("correlation_id"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                steps);
    }

    private String writeJson(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException ex) {
            return "{\"unserializable\":true}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return json.readValue(value, Map.class);
        } catch (JsonProcessingException ex) {
            return Map.of("raw", value);
        }
    }
}
