package com.axiom.automation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The shape of a rule definition document — what the no-code builder produces
 * and what the engine executes (FR-AUT-003, FR-AUT-006).
 *
 * <h2>Why one document rather than a step table</h2>
 * A builder with conditions, branches and loops produces a <em>tree</em>. A flat
 * ordered step table can only express a tree by adding a parent pointer and a
 * node discriminator, at which point it is a document with a join. Keeping the
 * definition as one jsonb value also makes FR-AUT-013 exact: a version IS the
 * document, so restoring one is a copy rather than a replay of edits.
 */
public final class RuleModel {

    private RuleModel() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Definition(TriggerSpec trigger, String entryCondition, List<Step> steps) {

        public List<Step> steps() {
            return steps == null ? List.of() : steps;
        }

        public TriggerSpec trigger() {
            return trigger == null ? new TriggerSpec("RECORD_CHANGE", List.of("CREATE", "UPDATE"), null)
                    : trigger;
        }
    }

    /**
     * @param events CREATE, UPDATE, DELETE, UNDELETE — FR-AUT-001 names all four
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TriggerSpec(String type, List<String> events, ScheduleSpec schedule) {

        public List<String> events() {
            return events == null || events.isEmpty() ? List.of("CREATE", "UPDATE") : events;
        }

        public boolean handles(String event) {
            return events().stream().anyMatch(e -> e.equalsIgnoreCase(event));
        }
    }

    /**
     * FR-AUT-002's three shapes, in one record.
     *
     * @param mode         FIXED_TIME, RECURRING or RELATIVE_TO_FIELD
     * @param runAt        FIXED_TIME: the instant, ISO-8601
     * @param everyMinutes RECURRING: the interval
     * @param dateField    RELATIVE_TO_FIELD: the record's date column
     * @param offsetDays   RELATIVE_TO_FIELD: negative is before the date, positive after
     * @param timeOfDay    RELATIVE_TO_FIELD / RECURRING: HH:mm the sweep should land on
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleSpec(String mode, String runAt, Integer everyMinutes,
                               String dateField, Integer offsetDays, String timeOfDay) {}

    /**
     * One node of the definition tree.
     *
     * <p>Every field is optional because the node type decides which apply; the
     * engine validates the ones its type needs and names the missing one rather
     * than failing on a null. A sealed hierarchy per node type would be tidier in
     * Java and considerably worse to author in a JSON document, which is the form
     * the builder actually round-trips.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
            String key,
            /** CONDITION, LOOP or ACTION. */
            String type,
            String label,

            // ---- CONDITION
            String expression,
            @JsonProperty("then") List<Step> thenSteps,
            @JsonProperty("else") List<Step> elseSteps,

            // ---- LOOP over related records
            String relatedObject,
            String relatedForeignKey,
            String itemAlias,
            Integer maxIterations,
            List<Step> body,

            // ---- ACTION
            String actionType,
            /** TRIGGERING or RELATED. */
            String target,
            String relatedIdField,
            String relatedObjectType,
            /** column → formula. The value is a formula, not a literal, so {@code 'text'} needs quotes. */
            Map<String, String> fields,

            // CREATE_RECORD
            String objectType,
            Map<String, String> values,

            // CREATE_TASK
            String subject,
            String priority,
            Integer dueInDays,
            String ownerField,

            // SEND_NOTIFICATION / SEND_EMAIL
            String recipientField,
            String emailTo,
            String title,
            String message,

            // SUBMIT_FOR_APPROVAL
            String approvalProcessCode,

            // INVOKE_WEBHOOK / CALL_INTEGRATION
            String webhookUrl,
            String integrationName,
            Map<String, String> payload) {

        public List<Step> thenSteps() { return thenSteps == null ? List.of() : thenSteps; }

        public List<Step> elseSteps() { return elseSteps == null ? List.of() : elseSteps; }

        public List<Step> body() { return body == null ? List.of() : body; }

        public Map<String, String> fields() { return fields == null ? Map.of() : fields; }

        public Map<String, String> values() { return values == null ? Map.of() : values; }

        public Map<String, String> payload() { return payload == null ? Map.of() : payload; }

        public String describe() {
            if (label != null && !label.isBlank()) return label;
            return type + (actionType == null ? "" : " " + actionType);
        }
    }

    /** The nine action verbs of FR-AUT-006, in one place so the builder and engine agree. */
    public static final List<String> ACTION_TYPES = List.of(
            "UPDATE_FIELDS", "CREATE_RECORD", "CREATE_TASK", "SEND_EMAIL", "SEND_NOTIFICATION",
            "SUBMIT_FOR_APPROVAL", "INVOKE_WEBHOOK", "CALL_INTEGRATION");

    /** Execution mode. The engine branches on this exactly once, in {@link ActionExecutor}. */
    public enum Mode { LIVE, DRY_RUN }

    /** One line of the per-run trace (FR-AUT-011) or of a simulation's would-be list (FR-AUT-010). */
    public record StepTrace(int stepNo, String stepKey, String stepType, String label,
                            String outcome, Map<String, Object> detail, long durationMs) {}

    /** The result of running one rule against one record. */
    public record ExecutionTrace(UUID ruleId, String ruleCode, String ruleName, int versionNo,
                                 String objectType, UUID recordId, String triggerType, String triggerEvent,
                                 boolean entryConditionMet, String entryConditionDetail,
                                 String status, String haltedReason, int actionsExecuted,
                                 int cascadeDepth, long durationMs, List<StepTrace> steps) {}
}
