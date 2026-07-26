package com.axiom.automation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The automation API (FRD §19).
 *
 * <p>Every route is behind {@code JwtAuthFilter}, so the tenant comes from the
 * verified session and never from a parameter. There is no tenant argument
 * anywhere in this class on purpose (FR-GLOBAL-001).
 */
@RestController
@RequestMapping("/api/v1/automation")
@Validated
public class AutomationController {

    private final RuleDefinitionService rules;
    private final SimulationService simulation;
    private final ExecutionLogService executions;
    private final ExpressionService expressions;
    private final ValidationRuleService validations;
    private final ObjectMetadataService metadata;
    private final ThrottleService throttle;
    private final ScheduleService schedules;
    private final AutomationEventConsumer consumer;
    private final AutomationRecordService records;
    private final RecordChangeDispatcher dispatcher;
    private final WorkflowGateService workflowGates;

    public AutomationController(RuleDefinitionService rules, SimulationService simulation,
                                ExecutionLogService executions, ExpressionService expressions,
                                ValidationRuleService validations, ObjectMetadataService metadata,
                                ThrottleService throttle, ScheduleService schedules,
                                AutomationEventConsumer consumer, AutomationRecordService records,
                                RecordChangeDispatcher dispatcher, WorkflowGateService workflowGates) {
        this.rules = rules;
        this.simulation = simulation;
        this.executions = executions;
        this.expressions = expressions;
        this.validations = validations;
        this.metadata = metadata;
        this.throttle = throttle;
        this.schedules = schedules;
        this.consumer = consumer;
        this.records = records;
        this.dispatcher = dispatcher;
        this.workflowGates = workflowGates;
    }

    // ------------------------------------------------------------------ metadata

    /** Objects, columns and date fields — what the no-code builder's pickers read. */
    @GetMapping("/objects")
    public List<ObjectMetadataService.ObjectDescriptor> objects() {
        AutomationAccess.requireRead();
        return metadata.list();
    }

    @GetMapping("/objects/{objectType}")
    public ObjectMetadataService.ObjectDescriptor object(@PathVariable String objectType) {
        AutomationAccess.requireRead();
        return metadata.describe(objectType);
    }

    @GetMapping("/functions")
    public Map<String, Object> functions() {
        AutomationAccess.requireRead();
        return Map.of("groups", expressions.functionCatalogue(),
                "actionTypes", RuleModel.ACTION_TYPES);
    }

    // ------------------------------------------------------------------ rules

    @GetMapping("/rules")
    public List<RuleDefinitionService.RuleView> listRules(
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String status) {
        return rules.list(objectType, status);
    }

    @GetMapping("/rules/{id}")
    public RuleDefinitionService.RuleView rule(@PathVariable UUID id) {
        return rules.get(id);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleDefinitionService.RuleView createRule(
            @RequestBody @Valid RuleDefinitionService.RuleMutation request) {
        return rules.create(request);
    }

    @PutMapping("/rules/{id}")
    public RuleDefinitionService.RuleView saveRuleVersion(
            @PathVariable UUID id, @RequestBody @Valid RuleDefinitionService.RuleMutation request) {
        return rules.saveVersion(id, request);
    }

    @GetMapping("/rules/{id}/versions")
    public List<RuleDefinitionService.RuleVersionView> versions(@PathVariable UUID id) {
        return rules.versions(id);
    }

    @PostMapping("/rules/{id}/versions/{versionNo}/restore")
    public RuleDefinitionService.RuleView restore(@PathVariable UUID id, @PathVariable int versionNo) {
        return rules.restoreVersion(id, versionNo);
    }

    @PostMapping("/rules/{id}/status")
    public RuleDefinitionService.RuleView status(@PathVariable UUID id,
                                                 @RequestBody Map<String, String> body) {
        return rules.setStatus(id, body.get("status"));
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        rules.delete(id);
    }

    // ------------------------------------------------------------------ simulation (FR-AUT-010)

    @PostMapping("/rules/{id}/simulate")
    public SimulationService.SimulationResult simulate(
            @PathVariable UUID id,
            @RequestBody(required = false) SimulationService.SimulationRequest request) {
        SimulationService.SimulationResult result = simulation.simulate(id, request);
        // Audited outside the read-only simulation transaction, on purpose.
        simulation.recordSimulationRun(id, result);
        return result;
    }

    // ------------------------------------------------------------------ execution log (FR-AUT-011)

    @GetMapping("/executions")
    public List<ExecutionLogService.ExecutionRow> executions(
            @RequestParam(required = false) UUID ruleId,
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) UUID recordId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        return executions.list(ruleId, objectType, recordId, status, limit);
    }

    @GetMapping("/executions/{id}")
    public ExecutionLogService.ExecutionRow execution(@PathVariable UUID id) {
        return executions.get(id);
    }

    @GetMapping("/executions/retention")
    public ExecutionLogService.RetentionPolicy retention() {
        return executions.retention();
    }

    @PutMapping("/executions/retention")
    public ExecutionLogService.RetentionPolicy setRetention(@RequestBody Map<String, Integer> body) {
        return executions.setRetention(body.getOrDefault("retainDays", 90));
    }

    @PostMapping("/executions/purge")
    public Map<String, Object> purge() {
        return Map.of("deleted", executions.purge());
    }

    // ------------------------------------------------------------------ expressions (FR-AUT-009)

    @PostMapping("/expressions/check")
    public ExpressionService.CheckResult check(@RequestBody @Valid ExpressionService.CheckRequest request) {
        return expressions.check(request);
    }

    @PostMapping("/expressions/evaluate")
    public ExpressionService.EvaluateResult evaluate(
            @RequestBody @Valid ExpressionService.EvaluateRequest request) {
        return expressions.evaluate(request);
    }

    // ------------------------------------------------------------------ validation rules (FR-AUT-005)

    @GetMapping("/validation-rules")
    public List<ValidationRuleService.ValidationRuleView> validationRules(
            @RequestParam(required = false) String objectType) {
        return validations.list(objectType);
    }

    @PostMapping("/validation-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public ValidationRuleService.ValidationRuleView createValidation(
            @RequestBody @Valid ValidationRuleService.ValidationMutation request) {
        return validations.create(request);
    }

    @PutMapping("/validation-rules/{id}")
    public ValidationRuleService.ValidationRuleView updateValidation(
            @PathVariable UUID id, @RequestBody @Valid ValidationRuleService.ValidationMutation request) {
        return validations.update(id, request);
    }

    @DeleteMapping("/validation-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteValidation(@PathVariable UUID id) {
        validations.delete(id);
    }

    // ------------------------------------------------------------------ telemetry (FR-AUT-014)

    @GetMapping("/telemetry")
    public ThrottleService.Telemetry telemetry() {
        return throttle.telemetry();
    }

    @PutMapping("/telemetry/policy")
    public ThrottleService.Policy policy(@RequestBody ThrottleService.Policy policy) {
        return throttle.updatePolicy(policy);
    }

    // ------------------------------------------------------------------ schedules (FR-AUT-002)

    @GetMapping("/schedules")
    public List<ScheduleService.ScheduleStatus> schedules() {
        return schedules.status();
    }

    @GetMapping("/schedules/{ruleId}/due")
    public List<Map<String, Object>> due(@PathVariable UUID ruleId) {
        return schedules.due(ruleId);
    }

    @PostMapping("/schedules/sweep")
    public ScheduleService.SweepResult sweep() {
        return schedules.sweep();
    }

    // ------------------------------------------------------------------ records and events

    /** Read a record through the automation metadata layer. */
    @GetMapping("/records/{objectType}/{recordId}")
    public Map<String, Object> readRecord(@PathVariable String objectType, @PathVariable UUID recordId) {
        return records.read(objectType, recordId);
    }

    /**
     * Governed update: validation rules, then the state machine, then the write,
     * then record-triggered automation — with the execution log in the response.
     */
    @PutMapping("/records/{objectType}/{recordId}")
    public AutomationRecordService.UpdateResult updateRecord(
            @PathVariable String objectType, @PathVariable UUID recordId,
            @RequestBody AutomationRecordService.UpdateRequest request) {
        return records.update(objectType, recordId, request);
    }

    // ------------------------------------------------------------------ workflow gates

    /**
     * Evaluate one record against its active workflow and persist the result.
     * The response is intentionally written in "what is missing / what next"
     * language so any screen can show it directly.
     */
    @GetMapping("/workflow-gates/{objectType}/{recordId}")
    public WorkflowGateService.GateStatus workflowGate(
            @PathVariable String objectType, @PathVariable UUID recordId) {
        return workflowGates.evaluate(objectType, recordId);
    }

    /**
     * Preview the exact transition a screen or API command intends to perform.
     * Optional proposed values let callers include fields collected by the
     * command form without changing the record during the check.
     */
    @PostMapping("/workflow-gates/{objectType}/{recordId}/transitions/{targetState}/check")
    public WorkflowGateService.GateStatus workflowTransitionGate(
            @PathVariable String objectType, @PathVariable UUID recordId,
            @PathVariable String targetState,
            @RequestBody(required = false) Map<String, Object> proposedValues) {
        return workflowGates.evaluateTransition(objectType, recordId, targetState,
                proposedValues == null ? Map.of() : proposedValues);
    }

    /** Latest workflow-gate status rows, optionally narrowed to blocked records. */
    @GetMapping("/workflow-gates")
    public List<WorkflowGateService.GateStatus> workflowGates(
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        return workflowGates.list(objectType, status, limit);
    }

    /**
     * The direct invocation path for the outbox consumer (ADR-003 degraded mode).
     * Calling it twice with the same eventKey has the effect of calling it once.
     */
    @PostMapping("/events")
    public AutomationEventConsumer.HandleResult handleEvent(@RequestBody Map<String, Object> body) {
        AutomationAccess.requireAdmin("replay automation events");
        String eventKey = String.valueOf(body.getOrDefault("eventKey", UUID.randomUUID().toString()));
        String aggregateType = String.valueOf(body.getOrDefault("aggregateType", ""));
        UUID aggregateId = UUID.fromString(String.valueOf(body.get("aggregateId")));
        String eventType = String.valueOf(body.getOrDefault("eventType", "record.updated"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return consumer.handle(eventKey, aggregateType, aggregateId, eventType, payload);
    }

    @PostMapping("/events/drain")
    public AutomationEventConsumer.DrainResult drain(@RequestParam(defaultValue = "50") int limit) {
        return consumer.drain(limit);
    }

    @GetMapping("/events/cursor")
    public Map<String, Object> cursor() {
        AutomationAccess.requireRead();
        return Map.of("consumer", "automation", "lastEventAt", consumer.cursor().toString());
    }

    /** Re-dispatch a record's current state through the rules, for a manual re-run. */
    @PostMapping("/dispatch/{objectType}/{recordId}")
    public RecordChangeDispatcher.DispatchResult dispatch(
            @PathVariable String objectType, @PathVariable UUID recordId,
            @RequestParam(defaultValue = "UPDATE") String event) {
        AutomationAccess.requireAdmin("re-dispatch records through automation");
        Map<String, Object> current = metadata.readRecord(objectType, recordId);
        return dispatcher.dispatch(objectType, recordId, event, Map.of(), current, 0);
    }
}
