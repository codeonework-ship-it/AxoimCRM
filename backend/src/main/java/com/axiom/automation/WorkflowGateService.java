package com.axiom.automation;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Workflow gates are the product-facing layer over the enforced process engine.
 *
 * <p>The process engine answers "may this transition happen?". This service
 * answers the question a user actually asks before they click: "what is missing
 * on this record, and what should I do next?" It evaluates the current state,
 * all outgoing transitions and target-state prerequisites, persists the latest
 * status, and appends an observation for audit/replay.
 */
@Service
public class WorkflowGateService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectMetadataService metadata;
    private final ProcessService processes;

    public WorkflowGateService(JdbcTemplate jdbc, ObjectMapper json, ObjectMetadataService metadata,
                               ProcessService processes) {
        this.jdbc = jdbc;
        this.json = json;
        this.metadata = metadata;
        this.processes = processes;
    }

    public record GateIssue(String code, String gate, String field, String message,
                            String nextAction, String targetState) {}

    public record GateStatus(UUID id, String objectType, UUID recordId, String processCode,
                             String currentState, String gateStatus, int missingCount,
                             String nextStep, List<GateIssue> issues, Instant evaluatedAt) {}

    @Transactional
    public GateStatus evaluate(String objectType, UUID recordId) {
        AutomationAccess.requireRead();
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(objectType);
        Map<String, Object> record = metadata.readRecord(object, recordId);
        if (record.isEmpty()) {
            throw new NotFoundException("No " + object.objectType() + " with id " + recordId);
        }

        ProcessService.ProcessView process = processes.activeFor(object.objectType());
        GateStatus status = process == null
                ? noProcess(object.objectType(), recordId)
                : evaluateProcess(object.objectType(), recordId, process, record);
        persist(status, process == null ? null : process.id());
        return status;
    }

    /**
     * Evaluates one intended transition instead of whichever outgoing path is
     * first in the process model.  The result is committed independently so a
     * refused business command still leaves durable evidence of what was
     * missing.  Proposed values are the values the command will write (for
     * example a signed-document reference supplied during contract activation).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public GateStatus evaluateTransition(String objectType, UUID recordId, String targetState,
                                         Map<String, Object> proposedValues) {
        AutomationAccess.requireRead();
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(objectType);
        Map<String, Object> stored = metadata.readRecord(object, recordId);
        if (stored.isEmpty()) {
            throw new NotFoundException("No " + object.objectType() + " with id " + recordId);
        }
        Map<String, Object> proposed = new LinkedHashMap<>(stored);
        if (proposedValues != null) proposed.putAll(proposedValues);

        ProcessService.ProcessView process = processes.activeFor(object.objectType());
        String requestedTarget = targetState == null ? "" : targetState.trim();
        String canonicalTarget = process == null ? requestedTarget : canonicalTarget(process, requestedTarget);
        GateStatus status = process == null
                ? noProcess(object.objectType(), recordId)
                : evaluateTargetProcess(object.objectType(), recordId, process, proposed, canonicalTarget);
        persist(status, process == null ? null : process.id());
        return status;
    }

    @Transactional(readOnly = true)
    public List<GateStatus> list(String objectType, String status, int limit) {
        AutomationAccess.requireRead();
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        StringBuilder where = new StringBuilder(" where tenant_id = ?");
        if (objectType != null && !objectType.isBlank()) {
            where.append(" and object_type = ?");
            args.add(objectType.toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isBlank()) {
            where.append(" and gate_status = ?");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        args.add(Math.max(1, Math.min(limit, 500)));
        return jdbc.query("""
                select id, object_type, record_id, process_code, current_state, gate_status,
                       missing_count, next_step, issues::text, evaluated_at
                from automation.workflow_gate_status
                """ + where + """

                order by evaluated_at desc
                limit ?
                """, (rs, i) -> new GateStatus(
                rs.getObject("id", UUID.class),
                rs.getString("object_type"),
                rs.getObject("record_id", UUID.class),
                rs.getString("process_code"),
                rs.getString("current_state"),
                rs.getString("gate_status"),
                rs.getInt("missing_count"),
                rs.getString("next_step"),
                readIssues(rs.getString("issues")),
                rs.getTimestamp("evaluated_at").toInstant()), args.toArray());
    }

    private GateStatus noProcess(String objectType, UUID recordId) {
        return new GateStatus(null, objectType, recordId, null, null, "NO_PROCESS", 0,
                "No active workflow is configured for this record type yet.", List.of(), Instant.now());
    }

    private GateStatus evaluateProcess(String objectType, UUID recordId, ProcessService.ProcessView process,
                                       Map<String, Object> record) {
        String currentState = text(record.get(process.stateField()));
        ProcessService.StateView current = process.states().stream()
                .filter(s -> Objects.equals(s.stateCode(), currentState)).findFirst().orElse(null);
        if (current == null) {
            List<GateIssue> issues = List.of(new GateIssue("UNKNOWN_STATE", "Current state",
                    process.stateField(),
                    "The record is in a state the workflow does not recognize: "
                            + (currentState == null || currentState.isBlank() ? "(blank)" : currentState) + ".",
                    "Choose one of the workflow states before continuing: "
                            + String.join(", ", process.states().stream().map(ProcessService.StateView::stateCode).toList()) + ".",
                    null));
            return status(objectType, recordId, process, currentState, "UNKNOWN_STATE", issues,
                    issues.getFirst().nextAction());
        }

        List<GateIssue> issues = new ArrayList<>();
        issues.addAll(missingFields(current, record, current.stateCode()));

        List<ProcessService.TransitionView> outgoing = process.transitions().stream()
                .filter(t -> Objects.equals(t.fromState(), current.stateCode()))
                .sorted(Comparator.comparing(t -> stateOrder(process, t.toState())))
                .toList();

        if (current.terminal() || outgoing.isEmpty()) {
            String terminalStep = current.terminal()
                    ? "This record has completed the workflow. No further step is required."
                    : "This state has no configured next transition. Ask an administrator to add the next workflow step.";
            return status(objectType, recordId, process, current.stateCode(),
                    issues.isEmpty() && current.terminal() ? "COMPLETED" : issues.isEmpty() ? "READY" : "BLOCKED",
                    issues, issues.isEmpty() ? terminalStep : firstAction(issues));
        }

        ProcessService.TransitionView bestBlocked = null;
        for (ProcessService.TransitionView transition : outgoing) {
            List<GateIssue> transitionIssues = transitionIssues(process, transition, record);
            if (transitionIssues.isEmpty() && issues.isEmpty()) {
                return status(objectType, recordId, process, current.stateCode(), "READY", List.of(),
                        "Ready for next step: " + transition.label() + " to " + transition.toState() + ".");
            }
            if (bestBlocked == null) {
                bestBlocked = transition;
                issues.addAll(transitionIssues);
            }
        }

        String next = issues.isEmpty() && bestBlocked != null
                ? "Ready for next step: " + bestBlocked.label() + " to " + bestBlocked.toState() + "."
                : firstAction(issues);
        return status(objectType, recordId, process, current.stateCode(),
                issues.isEmpty() ? "READY" : "BLOCKED", dedupe(issues), next);
    }

    private GateStatus evaluateTargetProcess(String objectType, UUID recordId,
                                             ProcessService.ProcessView process,
                                             Map<String, Object> record, String targetState) {
        String currentState = text(record.get(process.stateField()));
        ProcessService.StateView current = process.states().stream()
                .filter(s -> Objects.equals(s.stateCode(), currentState)).findFirst().orElse(null);
        if (current == null) {
            GateIssue issue = new GateIssue("UNKNOWN_STATE", "Current state", process.stateField(),
                    "The record is in a state the workflow does not recognize: "
                            + (currentState == null || currentState.isBlank() ? "(blank)" : currentState) + ".",
                    "Choose a recognized workflow state before continuing.", targetState);
            return status(objectType, recordId, process, currentState, "UNKNOWN_STATE",
                    List.of(issue), issue.nextAction());
        }

        ProcessService.TransitionView transition = process.transitions().stream()
                .filter(t -> Objects.equals(t.fromState(), current.stateCode())
                        && Objects.equals(t.toState(), targetState))
                .findFirst().orElse(null);
        if (transition == null) {
            List<String> permitted = process.transitions().stream()
                    .filter(t -> Objects.equals(t.fromState(), current.stateCode()))
                    .map(ProcessService.TransitionView::toState).sorted().toList();
            GateIssue issue = new GateIssue("TRANSITION_NOT_ALLOWED", "Workflow route",
                    process.stateField(),
                    "The workflow does not allow " + current.stateCode() + " to move directly to "
                            + targetState + ".",
                    permitted.isEmpty()
                            ? "This is a final workflow state; no further step is available."
                            : "Use the next permitted state: " + String.join(", ", permitted) + ".",
                    targetState);
            return status(objectType, recordId, process, current.stateCode(), "BLOCKED",
                    List.of(issue), issue.nextAction());
        }

        List<GateIssue> issues = new ArrayList<>(missingFields(current, record, current.stateCode()));
        issues.addAll(transitionIssues(process, transition, record));
        List<GateIssue> distinct = dedupe(issues);
        String next = distinct.isEmpty()
                ? "Ready for " + transition.label() + " to " + transition.toState() + "."
                : firstAction(distinct);
        return status(objectType, recordId, process, current.stateCode(),
                distinct.isEmpty() ? "READY" : "BLOCKED", distinct, next);
    }

    static List<GateIssue> transitionIssues(ProcessService.ProcessView process,
                                            ProcessService.TransitionView transition,
                                            Map<String, Object> record) {
        List<GateIssue> issues = new ArrayList<>();
        for (ProcessService.TransitionCondition condition : transition.conditions()) {
            Object actual = record.get(condition.field());
            if (!ProcessService.conditionHolds(actual, condition.op(), condition.value())) {
                String label = condition.label() == null || condition.label().isBlank()
                        ? plainField(condition.field()) + " must be " + conditionText(condition)
                        : condition.label();
                issues.add(new GateIssue(
                        "CONDITION_" + transition.toState() + "_" + condition.field(),
                        transition.label(),
                        condition.field(),
                        label + " Current value is " + (actual == null ? "blank" : text(actual)) + ".",
                        "Update " + plainField(condition.field()) + " so the record can move to "
                                + transition.toState() + ".",
                        transition.toState()));
            }
        }
        ProcessService.StateView target = process.states().stream()
                .filter(s -> Objects.equals(s.stateCode(), transition.toState()))
                .findFirst().orElse(null);
        if (target != null) {
            issues.addAll(missingFields(target, record, transition.toState()));
        }
        return issues;
    }

    private static List<GateIssue> missingFields(ProcessService.StateView state, Map<String, Object> record,
                                                 String targetState) {
        List<GateIssue> issues = new ArrayList<>();
        for (String field : state.mandatoryFields()) {
            Object value = record.get(field);
            if (value == null || text(value).isBlank()) {
                issues.add(new GateIssue("MISSING_" + targetState + "_" + field,
                        state.label(), field,
                        plainField(field) + " is required for " + state.label() + ".",
                        "Fill " + plainField(field) + " before moving to " + targetState + ".",
                        targetState));
            }
        }
        return issues;
    }

    private GateStatus status(String objectType, UUID recordId, ProcessService.ProcessView process,
                              String currentState, String gateStatus, List<GateIssue> issues,
                              String nextStep) {
        return new GateStatus(null, objectType, recordId, process.processCode(), currentState, gateStatus,
                issues.size(), nextStep, issues, Instant.now());
    }

    private void persist(GateStatus status, UUID processId) {
        UUID tenantId = TenantContext.get().tenantId();
        String issuesJson = writeIssues(status.issues());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into automation.workflow_gate_status
                  (id, tenant_id, object_type, record_id, process_id, process_code, current_state,
                   gate_status, missing_count, next_step, issues, evaluated_at, resolved_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now(),
                        case when ? in ('READY','COMPLETED','NO_PROCESS') then now() else null end)
                on conflict (tenant_id, object_type, record_id) do update
                  set process_id = excluded.process_id,
                      process_code = excluded.process_code,
                      current_state = excluded.current_state,
                      gate_status = excluded.gate_status,
                      missing_count = excluded.missing_count,
                      next_step = excluded.next_step,
                      issues = excluded.issues,
                      evaluated_at = now(),
                      resolved_at = case when excluded.gate_status in ('READY','COMPLETED','NO_PROCESS')
                                         then now() else null end
                """, id, tenantId, status.objectType(), status.recordId(), processId,
                status.processCode(), status.currentState(), status.gateStatus(),
                status.missingCount(), status.nextStep(), issuesJson, status.gateStatus());

        jdbc.update("""
                insert into automation.workflow_gate_observation
                  (id, tenant_id, object_type, record_id, process_id, process_code, current_state,
                   gate_status, missing_count, next_step, issues, observed_by, observed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, now())
                """, UUID.randomUUID(), tenantId, status.objectType(), status.recordId(), processId,
                status.processCode(), status.currentState(), status.gateStatus(),
                status.missingCount(), status.nextStep(), issuesJson, TenantContext.get().userId());
    }

    private List<GateIssue> readIssues(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return List.of(json.readValue(raw, GateIssue[].class));
        } catch (JsonProcessingException ex) {
            return List.of(new GateIssue("UNREADABLE_ISSUES", "Workflow gates", null,
                    "Stored workflow gate details could not be read.",
                    "Refresh the gate check to rebuild the details.", null));
        }
    }

    private String writeIssues(List<GateIssue> issues) {
        try {
            return json.writeValueAsString(issues);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Workflow gate issues could not be serialized", ex);
        }
    }

    static String firstAction(List<GateIssue> issues) {
        return issues.isEmpty() ? "No missing workflow steps were found." : issues.getFirst().nextAction();
    }

    static String plainField(String field) {
        if (field == null || field.isBlank()) return "this field";
        String spaced = field.replace('_', ' ').replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim();
        String lower = spaced.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }

    static String conditionText(ProcessService.TransitionCondition condition) {
        String value = condition.value() == null ? "" : " " + condition.value();
        return switch (condition.op() == null ? "" : condition.op().toUpperCase(Locale.ROOT)) {
            case "EQ" -> "equal to" + value;
            case "NEQ" -> "different from" + value;
            case "BLANK" -> "blank";
            case "NOT_BLANK" -> "filled in";
            case "IS_TRUE" -> "yes";
            case "IS_FALSE" -> "no";
            case "GT" -> "greater than" + value;
            case "GTE" -> "at least" + value;
            case "LT" -> "less than" + value;
            case "LTE" -> "at most" + value;
            case "IN" -> "one of " + String.valueOf(condition.value()).replace("|", ", ");
            default -> condition.op() + value;
        };
    }

    static String canonicalTarget(ProcessService.ProcessView process, String requestedTarget) {
        String requested = requestedTarget == null ? "" : requestedTarget.trim();
        return process.states().stream()
                .map(ProcessService.StateView::stateCode)
                .filter(state -> state.equalsIgnoreCase(requested))
                .findFirst().orElse(requested.toUpperCase(Locale.ROOT));
    }

    private static List<GateIssue> dedupe(List<GateIssue> issues) {
        Map<String, GateIssue> byCode = new LinkedHashMap<>();
        for (GateIssue issue : issues) byCode.putIfAbsent(issue.code(), issue);
        return List.copyOf(byCode.values());
    }

    private static int stateOrder(ProcessService.ProcessView process, String stateCode) {
        return process.states().stream()
                .filter(s -> Objects.equals(s.stateCode(), stateCode))
                .map(ProcessService.StateView::stateOrder)
                .findFirst().orElse(Integer.MAX_VALUE);
    }

    private static String text(Object value) {
        return value == null ? null : ExpressionEvaluator.text(value);
    }
}
