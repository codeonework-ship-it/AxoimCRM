package com.axiom.automation;

import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A governed, metadata-driven write path over any registered object — the API
 * side of "enforced server-side across UI, API and automation" (FR-AUT-004).
 *
 * <h2>Why the automation module owns a record-write endpoint at all</h2>
 * The engine must act on records belonging to every other epic <em>generically</em>,
 * without importing their services. That same generic path is what makes the
 * controls demonstrable end to end: one request runs validation rules
 * (FR-AUT-005), the state machine (FR-AUT-004), the write, and then the
 * record-triggered rules (FR-AUT-001) with the real old and new values — and
 * returns the execution log it produced.
 *
 * <h2>Order is load-bearing</h2>
 * Validation, then process, then write, then automation. Validation before the
 * process because "amount cannot be negative" is a better message than "no
 * transition to COMMIT"; the process before the write because a refused
 * transition must not leave a partial change; automation after the write because
 * a rule's entry condition is defined over what actually happened.
 */
@Service
public class AutomationRecordService {

    private final JdbcTemplate jdbc;
    private final ObjectMetadataService metadata;
    private final ValidationRuleService validations;
    private final ProcessService processes;
    private final RecordChangeDispatcher dispatcher;

    @Autowired
    public AutomationRecordService(JdbcTemplate jdbc, ObjectMetadataService metadata,
                                   ValidationRuleService validations, ProcessService processes,
                                   RecordChangeDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.validations = validations;
        this.processes = processes;
        this.dispatcher = dispatcher;
    }

    /** Values are literals here, not formulas — this is a data API, not a rule. */
    public record UpdateRequest(Map<String, Object> fields) {}

    public record UpdateResult(String objectType, UUID recordId, List<String> changedFields,
                               Map<String, Object> before, Map<String, Object> after,
                               RecordChangeDispatcher.DispatchResult automation) {}

    @Transactional(readOnly = true)
    public Map<String, Object> read(String objectType, UUID recordId) {
        AutomationAccess.requireRead();
        Map<String, Object> record = metadata.readRecord(objectType, recordId);
        if (record.isEmpty()) {
            throw new NotFoundException("No " + objectType + " with id " + recordId);
        }
        return stringify(record);
    }

    @Transactional
    public UpdateResult update(String objectType, UUID recordId, UpdateRequest request) {
        AutomationAccess.requireParticipant("change records through the automation API");
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(objectType);
        Map<String, Object> before = metadata.readRecord(object, recordId);
        if (before.isEmpty()) {
            throw new NotFoundException("No " + object.objectType() + " with id " + recordId);
        }
        if (request == null || request.fields() == null || request.fields().isEmpty()) {
            throw new IllegalArgumentException("Provide at least one field to change.");
        }

        Map<String, Object> assignments = new LinkedHashMap<>();
        request.fields().forEach((field, value) -> {
            String column = metadata.requireWritableColumn(object, field);
            assignments.put(column, ActionExecutor.coerce(object.columns().get(column), value));
        });

        Map<String, Object> proposed = new LinkedHashMap<>(before);
        proposed.putAll(assignments);

        // 1. validation rules (FR-AUT-005)
        validations.assertValid(object.objectType(), proposed, before);

        // 2. business process (FR-AUT-004) — refused here with a readable message,
        //    and refused again by the trigger for every path that is not this one.
        ProcessService.ProcessView process = processes.activeFor(object.objectType());
        if (process != null) {
            String field = process.stateField();
            String from = text(before.get(field));
            String to = text(proposed.get(field));
            processes.assertTransitionPermitted(object.objectType(), from, to, proposed);
        }

        // 3. the write. app.user_id is bound so the trigger's transition log can
        //    name the actor; TenantSessionAspect binds app.tenant_id already.
        jdbc.query("select set_config('app.user_id', ?, true)", rs -> null,
                TenantContext.get().userId().toString());

        StringBuilder sql = new StringBuilder("update ").append(object.qualifiedTable()).append(" set ");
        List<Object> args = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, Object> entry : assignments.entrySet()) {
            if (i++ > 0) sql.append(", ");
            sql.append(entry.getKey()).append(" = ?");
            if ("jsonb".equals(object.columns().get(entry.getKey()))) sql.append("::jsonb");
            args.add(entry.getValue());
        }
        if (object.columns().containsKey("updated_at")) sql.append(", updated_at = now()");
        sql.append(" where tenant_id = ? and ").append(object.idColumn()).append(" = ?");
        args.add(TenantContext.get().tenantId());
        args.add(recordId);

        try {
            jdbc.update(sql.toString(), args.toArray());
        } catch (DataAccessException ex) {
            throw translate(ex);
        }

        Map<String, Object> after = metadata.readRecord(object, recordId);
        List<String> changed = assignments.keySet().stream()
                .filter(c -> !java.util.Objects.equals(text(before.get(c)), text(after.get(c))))
                .toList();

        // 4. record-triggered automation (FR-AUT-001), with both value sets
        RecordChangeDispatcher.DispatchResult automation =
                dispatcher.dispatch(object.objectType(), recordId, "UPDATE", before, after, 0);

        return new UpdateResult(object.objectType(), recordId, changed,
                stringify(before), stringify(metadata.readRecord(object, recordId)), automation);
    }

    /**
     * Turn the state-machine trigger's refusal into the same 409 the API-side
     * check produces, so a caller sees one behaviour whichever layer caught it.
     */
    static RuntimeException translate(DataAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.contains("PROCESS_REFUSED")) {
                int at = message.indexOf("PROCESS_REFUSED:");
                String line = message.substring(at + "PROCESS_REFUSED:".length()).trim();
                int newline = line.indexOf('\n');
                if (newline > 0) line = line.substring(0, newline).trim();
                return new ConflictException(line);
            }
            cause = cause.getCause();
        }
        return ex;
    }

    private static String text(Object value) {
        return value == null ? null : ExpressionEvaluator.text(value);
    }

    /** JSON-safe view of a record: uuids, dates and numerics as strings. */
    static Map<String, Object> stringify(Map<String, Object> record) {
        Map<String, Object> out = new LinkedHashMap<>();
        record.forEach((k, v) -> out.put(k, v == null ? null
                : v instanceof Boolean || v instanceof Number ? v : ExpressionEvaluator.text(v)));
        return out;
    }
}
