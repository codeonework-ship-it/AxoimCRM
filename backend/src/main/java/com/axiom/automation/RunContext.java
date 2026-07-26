package com.axiom.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything one rule activation needs to evaluate itself against one record.
 *
 * <p>{@code oldValues} is carried even on a create (where it is empty) so that
 * every consumer — entry conditions, {@code ISCHANGED}, the trace — reads the
 * before-state from one place. FR-AUT-001 requires conditions over old and new
 * values; a context that only sometimes carries the old values makes that
 * requirement conditional on how the rule was triggered, which is the bug.
 */
public record RunContext(UUID ruleId,
                         String ruleCode,
                         String ruleName,
                         int versionNo,
                         ObjectMetadataService.ObjectDescriptor object,
                         UUID recordId,
                         Map<String, Object> newValues,
                         Map<String, Object> oldValues,
                         String triggerType,
                         String triggerEvent,
                         int cascadeDepth,
                         RuleModel.Mode mode) {

    public boolean dryRun() {
        return mode == RuleModel.Mode.DRY_RUN;
    }

    public ExpressionEvaluator.Context evaluation() {
        return new ExpressionEvaluator.Context(newValues, oldValues,
                "CREATE".equalsIgnoreCase(triggerEvent) || oldValues.isEmpty(), Map.of());
    }

    public ExpressionEvaluator.Context evaluation(Map<String, Object> loopVariables) {
        return evaluation().withVariables(loopVariables);
    }

    /** A copy with a refreshed after-state, used once a field update has landed. */
    public RunContext withNewValues(Map<String, Object> refreshed) {
        return new RunContext(ruleId, ruleCode, ruleName, versionNo, object, recordId,
                new LinkedHashMap<>(refreshed), oldValues, triggerType, triggerEvent, cascadeDepth, mode);
    }

    public RecursionGuard.Frame frame() {
        return new RecursionGuard.Frame(ruleCode, ruleName, object.objectType(), recordId);
    }
}
