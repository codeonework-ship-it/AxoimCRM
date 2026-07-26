package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The enforced business process of FR-AUT-004 — a Blueprint-style state machine
 * per object, deliberately chosen over scripted process (competitive analysis §6:
 * enforced process beats scripted process).
 *
 * <h2>This class is the second line, not the first</h2>
 * The enforcement that actually holds is the database trigger installed in V250.
 * FR-AUT-004 says a transition not in the model "cannot occur by any path", and a
 * check that only lives in a Java service is bypassed by the first caller that
 * writes SQL — a bulk import, another epic's service, a support session with psql.
 * So the trigger refuses it in the database and this class refuses it earlier, in
 * the API, purely so the caller gets a 409 with a sentence they can act on instead
 * of a constraint violation.
 *
 * <p>If the two ever disagree, the trigger wins and the disagreement is a defect
 * here, not there.
 */
@Service
public class ProcessService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectMetadataService metadata;
    private final AuditService audit;

    @Autowired
    public ProcessService(JdbcTemplate jdbc, ObjectMapper json, ObjectMetadataService metadata,
                          AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.metadata = metadata;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ contracts

    public record StateView(UUID id, String stateCode, String label, int stateOrder, boolean initial,
                            boolean terminal, List<String> mandatoryFields, Integer slaMinutes) {}

    public record TransitionCondition(String field, String op, String value, String label) {}

    public record TransitionView(UUID id, String fromState, String toState, String label,
                                 List<TransitionCondition> conditions, String requiredRole) {}

    public record ProcessView(UUID id, String processCode, String name, String objectType,
                              String stateField, String status, Instant updatedAt,
                              List<StateView> states, List<TransitionView> transitions) {}

    public record StateMutation(@NotBlank String stateCode, @NotBlank String label, Integer stateOrder,
                                Boolean initial, Boolean terminal, List<String> mandatoryFields,
                                Integer slaMinutes) {}

    public record TransitionMutation(@NotBlank String fromState, @NotBlank String toState,
                                     @NotBlank String label, List<TransitionCondition> conditions,
                                     String requiredRole) {}

    public record ProcessMutation(@NotBlank String processCode, @NotBlank String name,
                                  @NotBlank String objectType, @NotBlank String stateField,
                                  List<StateMutation> states, List<TransitionMutation> transitions) {}

    public record InstanceView(UUID id, String processCode, String objectType, UUID recordId,
                               String currentState, String previousState, Instant enteredAt,
                               Instant slaDueAt, boolean slaBreached) {}

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ProcessView> list() {
        AutomationAccess.requireRead();
        List<UUID> ids = jdbc.query(
                "select id from automation.process_definition where tenant_id = ? order by process_code",
                (rs, i) -> rs.getObject(1, UUID.class), TenantContext.get().tenantId());
        return ids.stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public ProcessView get(UUID id) {
        List<ProcessView> rows = jdbc.query("""
                select id, process_code, name, object_type, state_field, status, updated_at
                from automation.process_definition where tenant_id = ? and id = ?
                """, (rs, i) -> new ProcessView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7).toInstant(), List.of(), List.of()),
                TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("No business process with that id");
        ProcessView head = rows.getFirst();
        return new ProcessView(head.id(), head.processCode(), head.name(), head.objectType(),
                head.stateField(), head.status(), head.updatedAt(), states(id), transitions(id));
    }

    private List<StateView> states(UUID processId) {
        return jdbc.query("""
                select id, state_code, label, state_order, is_initial, is_terminal,
                       mandatory_fields, sla_minutes
                from automation.process_state where tenant_id = ? and process_id = ?
                order by state_order, state_code
                """, (rs, i) -> new StateView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getInt(4), rs.getBoolean(5), rs.getBoolean(6),
                        stringArray(rs.getArray(7)), (Integer) rs.getObject(8)),
                TenantContext.get().tenantId(), processId);
    }

    private List<TransitionView> transitions(UUID processId) {
        return jdbc.query("""
                select id, from_state, to_state, label, conditions::text, required_role
                from automation.process_transition where tenant_id = ? and process_id = ?
                order by from_state, to_state
                """, (rs, i) -> new TransitionView(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), readConditions(rs.getString(5)),
                        rs.getString(6)),
                TenantContext.get().tenantId(), processId);
    }

    /** The ACTIVE process governing an object, or null. */
    @Transactional(readOnly = true)
    public ProcessView activeFor(String objectType) {
        List<UUID> ids = jdbc.query("""
                select id from automation.process_definition
                where tenant_id = ? and object_type = ? and status = 'ACTIVE'
                """, (rs, i) -> rs.getObject(1, UUID.class), TenantContext.get().tenantId(),
                objectType.toUpperCase(Locale.ROOT));
        return ids.isEmpty() ? null : get(ids.getFirst());
    }

    @Transactional(readOnly = true)
    public List<InstanceView> instances(String objectType, UUID recordId) {
        AutomationAccess.requireRead();
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        StringBuilder filter = new StringBuilder();
        if (objectType != null && !objectType.isBlank()) {
            filter.append(" and i.object_type = ?");
            args.add(objectType.toUpperCase(Locale.ROOT));
        }
        if (recordId != null) { filter.append(" and i.record_id = ?"); args.add(recordId); }
        return jdbc.query("""
                select i.id, d.process_code, i.object_type, i.record_id, i.current_state,
                       i.previous_state, i.entered_at, i.sla_due_at
                from automation.process_instance i
                join automation.process_definition d on d.tenant_id = i.tenant_id and d.id = i.process_id
                where i.tenant_id = ?""" + filter + """

                order by i.entered_at desc limit 200
                """, (rs, i) -> {
                    Instant due = rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant();
                    return new InstanceView(rs.getObject(1, UUID.class), rs.getString(2),
                            rs.getString(3), rs.getObject(4, UUID.class), rs.getString(5),
                            rs.getString(6), rs.getTimestamp(7).toInstant(), due,
                            due != null && due.isBefore(Instant.now()));
                }, args.toArray());
    }

    // ------------------------------------------------------------------ enforcement

    /**
     * The API-side refusal. Names the unsatisfied condition, per FR-AUT-004's
     * "on failure" clause.
     *
     * @param proposed the full proposed after-state, so per-state mandatory fields
     *                 are checked against what the write would actually leave behind
     */
    @Transactional(readOnly = true)
    public void assertTransitionPermitted(String objectType, String fromState, String toState,
                                          Map<String, Object> proposed) {
        ProcessView process = activeFor(objectType);
        if (process == null) return;
        if (java.util.Objects.equals(fromState, toState)) {
            requireMandatoryFields(process, toState, proposed);
            return;
        }
        StateView target = process.states().stream()
                .filter(s -> s.stateCode().equals(toState)).findFirst()
                .orElseThrow(() -> new ConflictException("Business process \"" + process.processCode()
                        + "\" has no state \"" + toState + "\". Defined states: "
                        + process.states().stream().map(StateView::stateCode).toList()));

        TransitionView transition = process.transitions().stream()
                .filter(t -> t.fromState().equals(fromState) && t.toState().equals(toState))
                .findFirst().orElse(null);
        if (transition == null) {
            List<String> permitted = process.transitions().stream()
                    .filter(t -> t.fromState().equals(fromState))
                    .map(TransitionView::toState).sorted().toList();
            throw new ConflictException("Business process \"" + process.processCode()
                    + "\" defines no transition from \"" + fromState + "\" to \"" + toState
                    + "\". Permitted from \"" + fromState + "\": "
                    + (permitted.isEmpty() ? "(none — this is a terminal state)" : String.join(", ", permitted))
                    + ".");
        }
        for (TransitionCondition condition : transition.conditions()) {
            Object actual = proposed.get(condition.field());
            if (!conditionHolds(actual, condition.op(), condition.value())) {
                throw new ConflictException("Transition \"" + transition.label() + "\" ("
                        + fromState + " to " + toState + ") requires "
                        + (condition.label() == null || condition.label().isBlank()
                            ? condition.field() + " " + condition.op() + " " + condition.value()
                            : condition.label())
                        + ". Unsatisfied condition: " + condition.field() + " " + condition.op() + " "
                        + condition.value() + " (actual: "
                        + (actual == null ? "null" : ExpressionEvaluator.text(actual)) + ").");
            }
        }
        requireMandatoryFields(process, target.stateCode(), proposed);
    }

    private void requireMandatoryFields(ProcessView process, String stateCode,
                                        Map<String, Object> proposed) {
        process.states().stream().filter(s -> s.stateCode().equals(stateCode)).findFirst()
                .ifPresent(state -> {
                    for (String field : state.mandatoryFields()) {
                        Object value = proposed.get(field);
                        if (value == null || ExpressionEvaluator.text(value).isBlank()) {
                            throw new ConflictException("State \"" + stateCode + "\" of business process \""
                                    + process.processCode() + "\" requires \"" + field
                                    + "\" to be set before the record may enter it. "
                                    + "Unsatisfied condition: " + field + " is mandatory in " + stateCode
                                    + ".");
                        }
                    }
                });
    }

    /** Mirrors {@code automation.process_condition_holds} in V250, operator for operator. */
    static boolean conditionHolds(Object actual, String op, String expected) {
        String a = actual == null ? null : ExpressionEvaluator.text(actual);
        try {
            return switch (op == null ? "" : op.toUpperCase(Locale.ROOT)) {
                case "EQ" -> java.util.Objects.equals(a, expected);
                case "NEQ" -> !java.util.Objects.equals(a, expected);
                case "BLANK" -> a == null || a.isBlank();
                case "NOT_BLANK" -> a != null && !a.isBlank();
                case "IS_TRUE" -> "true".equals(a);
                case "IS_FALSE" -> "false".equals(a);
                case "GT" -> a != null && new java.math.BigDecimal(a)
                        .compareTo(new java.math.BigDecimal(expected)) > 0;
                case "GTE" -> a != null && new java.math.BigDecimal(a)
                        .compareTo(new java.math.BigDecimal(expected)) >= 0;
                case "LT" -> a != null && new java.math.BigDecimal(a)
                        .compareTo(new java.math.BigDecimal(expected)) < 0;
                case "LTE" -> a != null && new java.math.BigDecimal(a)
                        .compareTo(new java.math.BigDecimal(expected)) <= 0;
                case "IN" -> a != null && List.of(String.valueOf(expected).split("\\|")).contains(a);
                default -> false;
            };
        } catch (RuntimeException ex) {
            // A malformed comparison is an unsatisfied condition, never a satisfied one.
            return false;
        }
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public ProcessView create(ProcessMutation request) {
        AutomationAccess.requireAdmin("define business processes");
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(request.objectType());
        String stateColumn = metadata.requireColumn(object, request.stateField());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into automation.process_definition
                  (id, tenant_id, process_code, name, object_type, state_field, status, created_by)
                values (?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                """, id, TenantContext.get().tenantId(), request.processCode(), request.name(),
                object.objectType(), stateColumn, TenantContext.get().userId());
        replaceModel(id, object, request);
        audit.record("PROCESS_DEFINED", "AUTOMATION_PROCESS", id,
                "Defined business process " + request.processCode(),
                Map.of("processCode", request.processCode(), "objectType", object.objectType()));
        return get(id);
    }

    @Transactional
    public ProcessView update(UUID id, ProcessMutation request) {
        AutomationAccess.requireAdmin("change business processes");
        ProcessView existing = get(id);
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(request.objectType());
        String stateColumn = metadata.requireColumn(object, request.stateField());
        jdbc.update("""
                update automation.process_definition
                set name = ?, object_type = ?, state_field = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, request.name(), object.objectType(), stateColumn,
                TenantContext.get().tenantId(), id);
        replaceModel(id, object, request);
        audit.record("PROCESS_UPDATED", "AUTOMATION_PROCESS", id,
                "Updated business process " + existing.processCode(),
                Map.of("processCode", existing.processCode()));
        return get(id);
    }

    private void replaceModel(UUID processId, ObjectMetadataService.ObjectDescriptor object,
                              ProcessMutation request) {
        UUID tenantId = TenantContext.get().tenantId();
        jdbc.update("delete from automation.process_transition where tenant_id = ? and process_id = ?",
                tenantId, processId);
        jdbc.update("delete from automation.process_state where tenant_id = ? and process_id = ?",
                tenantId, processId);

        List<StateMutation> states = request.states() == null ? List.of() : request.states();
        if (states.isEmpty()) {
            throw new IllegalArgumentException("A business process needs at least one state.");
        }
        for (StateMutation state : states) {
            List<String> mandatory = state.mandatoryFields() == null ? List.of() : state.mandatoryFields();
            mandatory.forEach(f -> metadata.requireColumn(object, f));
            jdbc.update("""
                    insert into automation.process_state
                      (id, tenant_id, process_id, state_code, label, state_order, is_initial,
                       is_terminal, mandatory_fields, sla_minutes)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, processId, state.stateCode(), state.label(),
                    state.stateOrder() == null ? 100 : state.stateOrder(),
                    Boolean.TRUE.equals(state.initial()), Boolean.TRUE.equals(state.terminal()),
                    mandatory.toArray(new String[0]), state.slaMinutes());
        }
        List<String> codes = states.stream().map(StateMutation::stateCode).toList();
        for (TransitionMutation transition : request.transitions() == null ? List.<TransitionMutation>of()
                : request.transitions()) {
            if (!codes.contains(transition.fromState()) || !codes.contains(transition.toState())) {
                throw new IllegalArgumentException("Transition " + transition.fromState() + " → "
                        + transition.toState() + " references a state the process does not define.");
            }
            List<TransitionCondition> conditions =
                    transition.conditions() == null ? List.of() : transition.conditions();
            conditions.forEach(c -> metadata.requireColumn(object, c.field()));
            jdbc.update("""
                    insert into automation.process_transition
                      (id, tenant_id, process_id, from_state, to_state, label, conditions, required_role)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, UUID.randomUUID(), tenantId, processId, transition.fromState(),
                    transition.toState(), transition.label(), writeConditions(conditions),
                    transition.requiredRole());
        }
    }

    /**
     * Activating a process turns on database-level enforcement for every writer of
     * that table in this tenant. That is the point of FR-AUT-004 and it is also a
     * significant operational act, so it is audited with the model it activates.
     */
    @Transactional
    public ProcessView setStatus(UUID id, String status) {
        AutomationAccess.requireAdmin("activate or retire business processes");
        String target = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if (!List.of("DRAFT", "ACTIVE", "RETIRED").contains(target)) {
            throw new IllegalArgumentException("Status must be DRAFT, ACTIVE or RETIRED.");
        }
        ProcessView process = get(id);
        if ("ACTIVE".equals(target)) {
            if (process.states().stream().noneMatch(StateView::initial)) {
                throw new ConflictException("Mark one state as the entry state before activating "
                        + process.processCode() + "; without it no record could ever be created.");
            }
            ProcessView other = activeFor(process.objectType());
            if (other != null && !other.id().equals(id)) {
                throw new ConflictException("Business process " + other.processCode()
                        + " is already active on " + process.objectType()
                        + ". Retire it before activating another.");
            }
        }
        jdbc.update("""
                update automation.process_definition set status = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, target, TenantContext.get().tenantId(), id);
        audit.record("PROCESS_STATUS", "AUTOMATION_PROCESS", id,
                process.processCode() + " is now " + target,
                Map.of("processCode", process.processCode(), "from", process.status(), "to", target,
                        "objectType", process.objectType(),
                        "states", process.states().stream().map(StateView::stateCode).toList(),
                        "transitions", process.transitions().stream()
                                .map(t -> t.fromState() + "->" + t.toState()).toList()));
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        AutomationAccess.requireAdmin("delete business processes");
        ProcessView process = get(id);
        if ("ACTIVE".equals(process.status())) {
            throw new ConflictException("Retire " + process.processCode() + " before deleting it.");
        }
        jdbc.update("delete from automation.process_definition where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), id);
        audit.record("PROCESS_DELETED", "AUTOMATION_PROCESS", id,
                "Deleted business process " + process.processCode(),
                Map.of("processCode", process.processCode()));
    }

    // ------------------------------------------------------------------ plumbing

    private static List<String> stringArray(java.sql.Array array) throws java.sql.SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private List<TransitionCondition> readConditions(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return List.of(json.readValue(value, TransitionCondition[].class));
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private String writeConditions(List<TransitionCondition> conditions) {
        try {
            return json.writeValueAsString(conditions);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Transition conditions could not be serialized", ex);
        }
    }
}
