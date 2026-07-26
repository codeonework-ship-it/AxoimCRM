package com.axiom.automation;

import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The record-triggered entry point (FR-AUT-001) and the place loop protection is
 * applied (FR-AUT-012).
 *
 * <h2>Halting is data, not an exception</h2>
 * When a cascade would re-enter a rule already active on the same record, this
 * class does not throw — it records a HALTED execution carrying the diagnostic
 * and returns. An exception would unwind the very traces FR-AUT-011 exists to
 * produce, leaving the administrator with a 500 and no trail of the cascade that
 * caused it. The write that started the cascade has already landed and stays
 * landed; what is stopped is the next hop.
 *
 * <h2>Order of the two guards</h2>
 * The recursion check runs before the throttle. A cascade is a correctness
 * problem and a rate limit is a capacity problem; charging a cycle against the
 * tenant's fair-use budget would let a runaway pair of rules exhaust the budget
 * for every well-behaved rule in the tenant.
 */
@Service
public class RecordChangeDispatcher {

    private final RuleDefinitionService rules;
    private final RuleEngine engine;
    private final ObjectMetadataService metadata;
    private final ExecutionLogService log;
    private final ThrottleService throttle;

    @Autowired
    public RecordChangeDispatcher(RuleDefinitionService rules, RuleEngine engine,
                                  ObjectMetadataService metadata, ExecutionLogService log,
                                  ThrottleService throttle) {
        this.rules = rules;
        this.engine = engine;
        this.metadata = metadata;
        this.log = log;
        this.throttle = throttle;
    }

    public record ExecutionSummary(UUID executionId, String ruleCode, String ruleName, String status,
                                   boolean entryConditionMet, int actionsExecuted, long durationMs,
                                   String detail) {}

    public record DispatchResult(String objectType, UUID recordId, String event, int rulesConsidered,
                                 boolean halted, String haltDiagnostic,
                                 List<ExecutionSummary> executions) {}

    /**
     * Dispatch one record change to every ACTIVE rule that watches this object.
     *
     * @param event      CREATE, UPDATE, DELETE or UNDELETE
     * @param oldValues  the before-state; empty on CREATE
     * @param newValues  the after-state; on DELETE this is the last known state
     */
    @Transactional
    public DispatchResult dispatch(String objectType, UUID recordId, String event,
                                   Map<String, Object> oldValues, Map<String, Object> newValues,
                                   int cascadeDepth) {
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(objectType);
        List<RuleDefinitionService.ActiveRule> matching =
                rules.activeRecordChangeRules(object.objectType(), event);

        List<ExecutionSummary> summaries = new ArrayList<>();
        boolean halted = false;
        String diagnostic = null;
        int maxDepth = throttle.policy().maxCascadeDepth();

        for (RuleDefinitionService.ActiveRule rule : matching) {
            RunContext context = new RunContext(rule.id(), rule.ruleCode(), rule.name(), rule.versionNo(),
                    object, recordId,
                    newValues == null ? Map.of() : newValues,
                    oldValues == null ? Map.of() : oldValues,
                    rule.triggerType(), event, cascadeDepth, RuleModel.Mode.LIVE);

            // ---- guard 1: recursion (FR-AUT-012)
            java.util.Optional<String> cycle = RecursionGuard.cycleIfEntered(context.frame());
            if (cycle.isPresent()) {
                halted = true;
                diagnostic = cycle.get();
                summaries.add(halt(context, diagnostic, "CYCLE"));
                continue;
            }
            if (cascadeDepth > maxDepth) {
                halted = true;
                diagnostic = "Automation halted: the cascade reached depth " + cascadeDepth
                        + ", beyond this tenant's limit of " + maxDepth
                        + ". Activation path: " + RecursionGuard.describePath()
                        + " → " + rule.ruleCode()
                        + ". Participating rules: " + participants(rule);
                summaries.add(halt(context, diagnostic, "DEPTH"));
                continue;
            }

            // ---- guard 2: fair use (FR-AUT-014) — a rate limit, never a rule limit
            ThrottleService.Decision decision = throttle.acquire();
            if (!decision.allowed()) {
                UUID id = log.recordThrottled(rule, object.objectType(), recordId, event,
                        decision.message(), cascadeDepth);
                summaries.add(new ExecutionSummary(id, rule.ruleCode(), rule.name(), "THROTTLED",
                        false, 0, 0, decision.message()));
                continue;
            }

            RecursionGuard.push(context.frame());
            RuleModel.ExecutionTrace trace;
            try {
                trace = engine.run(context, rule.definition());
            } finally {
                RecursionGuard.pop();
            }
            UUID executionId = log.record(trace);
            if ("HALTED".equals(trace.status())) {
                halted = true;
                diagnostic = trace.haltedReason();
            }
            summaries.add(new ExecutionSummary(executionId, rule.ruleCode(), rule.name(),
                    trace.status(), trace.entryConditionMet(), trace.actionsExecuted(),
                    trace.durationMs(), trace.entryConditionDetail()));
        }

        return new DispatchResult(object.objectType(), recordId, event, matching.size(),
                halted, diagnostic, summaries);
    }

    private ExecutionSummary halt(RunContext context, String diagnostic, String kind) {
        RuleModel.ExecutionTrace trace = new RuleModel.ExecutionTrace(
                context.ruleId(), context.ruleCode(), context.ruleName(), context.versionNo(),
                context.object().objectType(), context.recordId(), context.triggerType(),
                context.triggerEvent(), false, diagnostic, "HALTED", diagnostic, 0,
                context.cascadeDepth(), 0,
                List.of(new RuleModel.StepTrace(1, "guard", "GUARD", "Recursion guard", "HALTED",
                        Map.of("kind", kind, "diagnostic", diagnostic,
                                "activationPath", RecursionGuard.describePath()), 0)));
        UUID id = log.record(trace);
        return new ExecutionSummary(id, context.ruleCode(), context.ruleName(), "HALTED",
                false, 0, 0, diagnostic);
    }

    private String participants(RuleDefinitionService.ActiveRule rule) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        RecursionGuard.path().forEach(f -> names.add(f.ruleCode() + " (" + f.ruleName() + ")"));
        names.add(rule.ruleCode() + " (" + rule.name() + ")");
        return String.join(", ", names);
    }

    /** Present so a trace can name the acting principal without another lookup. */
    public UUID actingUser() {
        return TenantContext.get().userId();
    }
}
