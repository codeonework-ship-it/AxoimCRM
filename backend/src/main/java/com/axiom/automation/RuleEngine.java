package com.axiom.automation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Walks a rule definition against one record and produces a trace.
 *
 * <h2>One engine, two modes</h2>
 * FR-AUT-010's simulation is not a second implementation of the engine — it is
 * this engine with {@link RuleModel.Mode#DRY_RUN}. That is the only way the
 * simulation can be trusted to predict the live run: a parallel "preview"
 * implementation drifts from the real one the first time either is edited, and
 * the drift is invisible until a rule does something in production that the
 * preview said it would not.
 *
 * <h2>The trace is produced whatever happens</h2>
 * A step that throws becomes a FAILED trace line, not an exception out of
 * {@link #run}. FR-AUT-011 requires a log of each step and each action result;
 * a run that aborts with a stack trace produces neither.
 */
@Component
public class RuleEngine {

    private final ActionExecutor actions;
    private final ObjectMetadataService metadata;

    @Autowired
    public RuleEngine(ActionExecutor actions, ObjectMetadataService metadata) {
        this.actions = actions;
        this.metadata = metadata;
    }

    /** Mutable state for one walk; kept off the fields so the bean stays stateless. */
    private static final class Walk {
        RunContext context;
        final List<RuleModel.StepTrace> traces = new ArrayList<>();
        int stepNo;
        int actionsExecuted;
        boolean failed;
        String haltedReason;

        Walk(RunContext context) { this.context = context; }

        void add(String key, String type, String label, String outcome,
                 Map<String, Object> detail, long durationMs) {
            traces.add(new RuleModel.StepTrace(++stepNo, key, type, label, outcome,
                    detail == null ? Map.of() : detail, durationMs));
        }
    }

    public RuleModel.ExecutionTrace run(RunContext context, RuleModel.Definition definition) {
        long started = System.nanoTime();
        Walk walk = new Walk(context);

        boolean entryMet;
        String entryDetail;
        String entryCondition = definition.entryCondition();
        if (entryCondition == null || entryCondition.isBlank()) {
            entryMet = true;
            entryDetail = "No entry condition; the rule runs for every matching event.";
        } else {
            try {
                entryMet = ExpressionEvaluator.condition(entryCondition, context.evaluation());
                entryDetail = entryCondition + " → " + entryMet
                        + " (evaluated against " + (context.oldValues().isEmpty()
                            ? "the created record" : "old and new values") + ")";
            } catch (ExpressionSyntaxException ex) {
                return trace(context, false,
                        "Entry condition is not a valid formula: " + ex.getMessage()
                                + " (position " + ex.position() + ")",
                        "FAILED", null, 0, walk.traces, started);
            } catch (ExpressionEvaluator.EvaluationException ex) {
                return trace(context, false, "Entry condition could not be evaluated: " + ex.getMessage(),
                        "FAILED", null, 0, walk.traces, started);
            }
        }

        if (!entryMet) {
            return trace(context, false, entryDetail, "SKIPPED", null, 0, walk.traces, started);
        }

        execute(definition.steps(), walk, Map.of());

        String status = walk.haltedReason != null ? "HALTED" : walk.failed ? "FAILED" : "SUCCEEDED";
        return trace(walk.context, true, entryDetail, status, walk.haltedReason,
                walk.actionsExecuted, walk.traces, started);
    }

    // ------------------------------------------------------------------ step walk

    private void execute(List<RuleModel.Step> steps, Walk walk, Map<String, Object> loopVars) {
        for (RuleModel.Step step : steps) {
            if (walk.haltedReason != null) {
                walk.add(step.key(), typeOf(step), step.describe(), "NOT_REACHED",
                        Map.of("reason", "The run was halted before this step."), 0);
                continue;
            }
            switch (typeOf(step)) {
                case "CONDITION" -> condition(step, walk, loopVars);
                case "LOOP" -> loop(step, walk, loopVars);
                default -> action(step, walk, loopVars);
            }
        }
    }

    private void condition(RuleModel.Step step, Walk walk, Map<String, Object> loopVars) {
        long t0 = System.nanoTime();
        boolean result;
        try {
            result = ExpressionEvaluator.condition(step.expression(), walk.context.evaluation(loopVars));
        } catch (RuntimeException ex) {
            walk.failed = true;
            walk.add(step.key(), "CONDITION", step.describe(), "FAILED",
                    Map.of("expression", String.valueOf(step.expression()),
                            "error", String.valueOf(ex.getMessage())), ms(t0));
            return;
        }
        walk.add(step.key(), "CONDITION", step.describe(), result ? "TRUE" : "FALSE",
                Map.of("expression", String.valueOf(step.expression()), "result", result), ms(t0));
        execute(result ? step.thenSteps() : step.elseSteps(), walk, loopVars);
    }

    private void loop(RuleModel.Step step, Walk walk, Map<String, Object> loopVars) {
        long t0 = System.nanoTime();
        List<Map<String, Object>> children;
        try {
            children = metadata.readRelated(step.relatedObject(), step.relatedForeignKey(),
                    walk.context.recordId(),
                    step.maxIterations() == null ? 100 : step.maxIterations());
        } catch (RuntimeException ex) {
            walk.failed = true;
            walk.add(step.key(), "LOOP", step.describe(), "FAILED",
                    Map.of("error", String.valueOf(ex.getMessage())), ms(t0));
            return;
        }
        walk.add(step.key(), "LOOP", step.describe(), "ITERATING",
                Map.of("relatedObject", String.valueOf(step.relatedObject()),
                        "matched", children.size()), ms(t0));

        int index = 0;
        for (Map<String, Object> child : children) {
            if (walk.haltedReason != null) break;
            index++;
            // Inside a loop a bare field name is the CHILD's field; NEW./OLD. still
            // reach the triggering record. One rule, no alias bookkeeping.
            Map<String, Object> vars = new LinkedHashMap<>(loopVars);
            vars.putAll(child);
            walk.add(step.key() + "#" + index, "LOOP_ITEM", "Iteration " + index, "ENTERED",
                    Map.of("recordId", String.valueOf(child.get("id"))), 0);
            execute(step.body(), walk, vars);
        }
    }

    private void action(RuleModel.Step step, Walk walk, Map<String, Object> loopVars) {
        long t0 = System.nanoTime();
        ActionExecutor.ActionResult result = actions.perform(step, walk.context, loopVars);
        Map<String, Object> detail = new LinkedHashMap<>(result.detail());
        detail.put("description", result.description());
        walk.add(step.key(), "ACTION:" + String.valueOf(step.actionType()).toUpperCase(Locale.ROOT),
                step.describe(), result.outcome(), detail, ms(t0));

        if ("FAILED".equals(result.outcome())) {
            walk.failed = true;
            return;
        }
        if (result.executed()) walk.actionsExecuted++;
        if (result.cascadeHalt() != null) {
            walk.haltedReason = result.cascadeHalt();
            walk.add(step.key(), "GUARD", "Recursion guard", "HALTED",
                    Map.of("diagnostic", result.cascadeHalt(),
                            "activationPath", RecursionGuard.describePath()), 0);
            return;
        }
        // Keep the in-memory record faithful so later steps and later rules see
        // what the update did — in DRY_RUN from the would-be assignments, in LIVE
        // from the database, which is the authority once the write has landed.
        if ("UPDATE_FIELDS".equalsIgnoreCase(step.actionType())
                && !"RELATED".equalsIgnoreCase(step.target())) {
            if (walk.context.dryRun()) {
                Object assignments = result.detail().get("assignments");
                if (assignments instanceof Map<?, ?> map) {
                    Map<String, Object> merged = new LinkedHashMap<>(walk.context.newValues());
                    map.forEach((k, v) -> merged.put(String.valueOf(k), v));
                    walk.context = walk.context.withNewValues(merged);
                }
            } else if (result.executed()) {
                walk.context = walk.context.withNewValues(
                        metadata.readRecord(walk.context.object(), walk.context.recordId()));
            }
        }
    }

    private static String typeOf(RuleModel.Step step) {
        String type = step.type() == null ? "ACTION" : step.type().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "CONDITION", "BRANCH", "IF" -> "CONDITION";
            case "LOOP", "FOR_EACH" -> "LOOP";
            default -> "ACTION";
        };
    }

    private static long ms(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static RuleModel.ExecutionTrace trace(RunContext c, boolean entryMet, String entryDetail,
                                                  String status, String halted, int actionsExecuted,
                                                  List<RuleModel.StepTrace> steps, long startNanos) {
        return new RuleModel.ExecutionTrace(c.ruleId(), c.ruleCode(), c.ruleName(), c.versionNo(),
                c.object().objectType(), c.recordId(), c.triggerType(), c.triggerEvent(),
                entryMet, entryDetail, status, halted, actionsExecuted, c.cascadeDepth(),
                ms(startNanos), List.copyOf(steps));
    }
}
