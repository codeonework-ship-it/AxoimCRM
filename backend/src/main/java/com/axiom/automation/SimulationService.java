package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dry-run simulation against real records (FR-AUT-010, {@code F-230}).
 *
 * <h2>Three independent reasons nothing can be written</h2>
 * The requirement is that every action that <em>would</em> occur is shown and
 * <em>none of them occur</em>. One guarantee would be a promise; these are three,
 * and they fail independently:
 * <ol>
 *   <li>{@link ActionExecutor} returns a description instead of acting whenever
 *       the mode is DRY_RUN — the only branch in the module where "would" becomes
 *       "did".</li>
 *   <li>Nothing on this path writes an execution log. The simulation result is
 *       returned to the caller and never persisted, so there is no writer to get
 *       wrong.</li>
 *   <li>This method runs in a {@code readOnly} transaction, so PostgreSQL itself
 *       refuses any INSERT, UPDATE or DELETE that a future edit accidentally
 *       introduces. A code review can be wrong; the database cannot be talked
 *       into it.</li>
 * </ol>
 * The unit test then asserts the property from the outside — no mutating call
 * reaches the JdbcTemplate at all — which is the check that survives refactoring.
 *
 * <h2>Real records, not fixtures</h2>
 * The administrator names the records or takes a sample of the object. Simulating
 * against invented data answers a question nobody asked; the question is "what
 * will this do to <em>my</em> pipeline on Monday".
 */
@Service
public class SimulationService {

    private final RuleDefinitionService rules;
    private final RuleEngine engine;
    private final ObjectMetadataService metadata;
    private final AuditService audit;

    @Autowired
    public SimulationService(RuleDefinitionService rules, RuleEngine engine,
                             ObjectMetadataService metadata, AuditService audit) {
        this.rules = rules;
        this.engine = engine;
        this.metadata = metadata;
        this.audit = audit;
    }

    /**
     * @param recordIds        explicit subjects; when empty, a sample of the object is used
     * @param sampleSize       size of that sample
     * @param proposedChanges  a hypothetical edit applied in memory, so entry
     *                         conditions over old AND new values can be exercised
     * @param event            CREATE, UPDATE, DELETE or UNDELETE
     */
    public record SimulationRequest(List<UUID> recordIds, Integer sampleSize,
                                    Map<String, Object> proposedChanges, String event,
                                    Integer versionNo) {}

    public record WouldBeAction(UUID recordId, String recordLabel, String stepKey, String stepType,
                                String actionType, String description, Map<String, Object> detail) {}

    public record RecordSimulation(UUID recordId, String recordLabel, boolean entryConditionMet,
                                   String entryConditionDetail, String status,
                                   List<RuleModel.StepTrace> steps) {}

    public record SimulationResult(String ruleCode, String ruleName, String objectType, int versionNo,
                                   String event, int recordsEvaluated, int recordsMatched,
                                   int wouldBeActionCount, boolean anythingWasWritten,
                                   String guarantee, List<RecordSimulation> records,
                                   List<WouldBeAction> wouldBeActions) {}

    @Transactional(readOnly = true)
    public SimulationResult simulate(UUID ruleId, SimulationRequest request) {
        AutomationAccess.requireAdmin("simulate automation rules");
        RuleDefinitionService.RuleView rule = rules.get(ruleId);
        RuleModel.Definition definition = request != null && request.versionNo() != null
                ? versionDefinition(ruleId, request.versionNo())
                : rule.definition();
        int versionNo = request != null && request.versionNo() != null
                ? request.versionNo() : rule.activeVersionNo();

        ObjectMetadataService.ObjectDescriptor object = metadata.describe(rule.objectType());
        String event = request == null || request.event() == null || request.event().isBlank()
                ? "UPDATE" : request.event().toUpperCase(java.util.Locale.ROOT);

        List<Map<String, Object>> subjects = subjects(rule.objectType(), request);
        Map<String, Object> proposed = request == null || request.proposedChanges() == null
                ? Map.of() : request.proposedChanges();

        List<RecordSimulation> records = new ArrayList<>();
        List<WouldBeAction> wouldBe = new ArrayList<>();
        int matched = 0;

        for (Map<String, Object> record : subjects) {
            UUID recordId = (UUID) record.get(object.idColumn());
            Map<String, Object> oldValues = new LinkedHashMap<>(record);
            Map<String, Object> newValues = new LinkedHashMap<>(record);
            proposed.forEach((field, value) -> {
                String column = metadata.requireColumn(object, field);
                newValues.put(column, ActionExecutor.coerce(object.columns().get(column), value));
            });

            RunContext context = new RunContext(rule.id(), rule.ruleCode(), rule.name(), versionNo,
                    object, recordId, newValues,
                    "CREATE".equals(event) ? Map.of() : oldValues,
                    rule.triggerType(), event, 0, RuleModel.Mode.DRY_RUN);

            RuleModel.ExecutionTrace trace = engine.run(context, definition);
            String label = label(record);
            if (trace.entryConditionMet()) matched++;
            records.add(new RecordSimulation(recordId, label, trace.entryConditionMet(),
                    trace.entryConditionDetail(), trace.status(), trace.steps()));

            for (RuleModel.StepTrace step : trace.steps()) {
                if (!step.stepType().startsWith("ACTION:")) continue;
                if (!"WOULD_EXECUTE".equals(step.outcome())) continue;
                wouldBe.add(new WouldBeAction(recordId, label, step.stepKey(), step.stepType(),
                        step.stepType().substring("ACTION:".length()),
                        String.valueOf(step.detail().get("description")), step.detail()));
            }
        }

        return new SimulationResult(rule.ruleCode(), rule.name(), object.objectType(), versionNo, event,
                subjects.size(), matched, wouldBe.size(), false,
                "Read-only simulation: the engine ran in DRY_RUN, no execution log was written, and "
                        + "the transaction was opened read-only so PostgreSQL would refuse any write. "
                        + "Every action below is what WOULD happen; none of them happened.",
                records, wouldBe);
    }

    /**
     * Audited separately from {@link #simulate} because auditing is a write and the
     * simulation transaction is read-only — deliberately, so the guarantee cannot be
     * weakened by adding "just one" insert to the simulation path later.
     */
    @Transactional
    public void recordSimulationRun(UUID ruleId, SimulationResult result) {
        audit.record("AUTOMATION_SIMULATED", "AUTOMATION_RULE", ruleId,
                "Simulated " + result.ruleCode() + " over " + result.recordsEvaluated() + " record(s)",
                Map.of("ruleCode", result.ruleCode(), "recordsEvaluated", result.recordsEvaluated(),
                        "recordsMatched", result.recordsMatched(),
                        "wouldBeActions", result.wouldBeActionCount(),
                        "actionsPerformed", 0));
    }

    private List<Map<String, Object>> subjects(String objectType, SimulationRequest request) {
        if (request != null && request.recordIds() != null && !request.recordIds().isEmpty()) {
            ObjectMetadataService.ObjectDescriptor object = metadata.describe(objectType);
            List<Map<String, Object>> out = new ArrayList<>();
            for (UUID id : request.recordIds()) {
                Map<String, Object> record = metadata.readRecord(object, id);
                if (!record.isEmpty()) out.add(record);
            }
            return out;
        }
        int size = request == null || request.sampleSize() == null ? 10 : request.sampleSize();
        return metadata.sample(objectType, size);
    }

    private static String label(Map<String, Object> record) {
        for (String candidate : List.of("name", "subject", "company", "title", "email")) {
            Object value = record.get(candidate);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return String.valueOf(record.get("id"));
    }

    private RuleModel.Definition versionDefinition(UUID ruleId, int versionNo) {
        return rules.versions(ruleId).stream()
                .filter(v -> v.versionNo() == versionNo)
                .map(RuleDefinitionService.RuleVersionView::definition)
                .findFirst()
                .orElseThrow(() -> new com.axiom.common.NotFoundException(
                        "This rule has no version " + versionNo));
    }

    /** Present so a trace can name the simulating principal without another lookup. */
    public UUID simulatedBy() {
        return TenantContext.get().userId();
    }
}
