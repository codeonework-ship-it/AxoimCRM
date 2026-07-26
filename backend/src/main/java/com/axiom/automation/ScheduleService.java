package com.axiom.automation;

import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled automation (FR-AUT-002): fixed time, recurrence, or relative to a
 * record date field.
 *
 * <h2>Relative-to-a-date-field is the interesting one</h2>
 * The other two modes are clocks. This one is a query: "every opportunity whose
 * close date is thirty days from today". It is expressed as an offset against a
 * column rather than as a stored due-date per record, because a record whose
 * close date moves must move with it — a materialised per-record schedule would
 * fire on the old date and quietly stop being correct the first time anyone
 * rescheduled a deal.
 *
 * <h2>Invoked, not self-starting</h2>
 * The sweep is an endpoint rather than an {@code @Scheduled} method because a
 * scheduler thread has no tenant context and no principal, and binding a fake one
 * to satisfy RLS is how cross-tenant defects get written. An operator, cron or
 * platform scheduler calls it per tenant with a real session.
 */
@Service
public class ScheduleService {

    private final JdbcTemplate jdbc;
    private final RuleDefinitionService rules;
    private final RuleEngine engine;
    private final ObjectMetadataService metadata;
    private final ExecutionLogService log;
    private final ThrottleService throttle;

    @Autowired
    public ScheduleService(JdbcTemplate jdbc, RuleDefinitionService rules, RuleEngine engine,
                           ObjectMetadataService metadata, ExecutionLogService log,
                           ThrottleService throttle) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.engine = engine;
        this.metadata = metadata;
        this.log = log;
        this.throttle = throttle;
    }

    public record ScheduleStatus(UUID ruleId, String ruleCode, String name, String mode,
                                 String description, Instant lastFiredAt, Instant nextDueAt,
                                 boolean dueNow, int matchingRecords) {}

    public record SweepOutcome(String ruleCode, String mode, int recordsMatched, int executions,
                               int actionsExecuted, String detail) {}

    public record SweepResult(Instant sweptAt, int rulesConsidered, int rulesFired,
                              List<SweepOutcome> outcomes) {}

    // ------------------------------------------------------------------ status

    @Transactional(readOnly = true)
    public List<ScheduleStatus> status() {
        AutomationAccess.requireRead();
        Map<UUID, Instant[]> state = scheduleState();
        List<ScheduleStatus> out = new ArrayList<>();
        for (RuleDefinitionService.ActiveRule rule : rules.activeScheduledRules()) {
            RuleModel.ScheduleSpec spec = rule.definition().trigger().schedule();
            Instant[] times = state.getOrDefault(rule.id(), new Instant[]{null, null});
            int matching = 0;
            if (spec != null && "RELATIVE_TO_FIELD".equalsIgnoreCase(spec.mode())) {
                matching = matchingRecords(rule, spec).size();
            }
            out.add(new ScheduleStatus(rule.id(), rule.ruleCode(), rule.name(),
                    spec == null ? "UNSET" : spec.mode(), describe(spec), times[0], times[1],
                    isDue(spec, times[0]), matching));
        }
        return out;
    }

    private static String describe(RuleModel.ScheduleSpec spec) {
        if (spec == null) return "No schedule configured.";
        return switch (spec.mode() == null ? "" : spec.mode().toUpperCase(Locale.ROOT)) {
            case "FIXED_TIME" -> "Once, at " + spec.runAt() + ".";
            case "RECURRING" -> "Every " + spec.everyMinutes() + " minutes.";
            case "RELATIVE_TO_FIELD" -> (spec.offsetDays() == null ? 0 : Math.abs(spec.offsetDays()))
                    + " days " + ((spec.offsetDays() == null ? 0 : spec.offsetDays()) < 0 ? "before" : "after")
                    + " " + spec.dateField()
                    + (spec.timeOfDay() == null ? "" : " at " + spec.timeOfDay()) + ".";
            default -> "Unrecognised schedule mode " + spec.mode() + ".";
        };
    }

    // ------------------------------------------------------------------ sweep

    /** Runs every ACTIVE scheduled rule that is due. */
    @Transactional
    public SweepResult sweep() {
        AutomationAccess.requireAdmin("run the automation schedule sweep");
        List<RuleDefinitionService.ActiveRule> scheduled = rules.activeScheduledRules();
        Map<UUID, Instant[]> state = scheduleState();
        List<SweepOutcome> outcomes = new ArrayList<>();
        int fired = 0;

        for (RuleDefinitionService.ActiveRule rule : scheduled) {
            RuleModel.ScheduleSpec spec = rule.definition().trigger().schedule();
            Instant lastFired = state.getOrDefault(rule.id(), new Instant[]{null, null})[0];
            if (!isDue(spec, lastFired)) {
                outcomes.add(new SweepOutcome(rule.ruleCode(), spec == null ? "UNSET" : spec.mode(),
                        0, 0, 0, "Not due: " + describe(spec)));
                continue;
            }
            fired++;
            List<Map<String, Object>> subjects = matchingRecords(rule, spec);
            int executions = 0;
            int actions = 0;
            for (Map<String, Object> record : subjects) {
                ThrottleService.Decision decision = throttle.acquire();
                ObjectMetadataService.ObjectDescriptor object = metadata.describe(rule.objectType());
                UUID recordId = (UUID) record.get(object.idColumn());
                if (!decision.allowed()) {
                    log.recordThrottled(rule, object.objectType(), recordId, "SCHEDULED",
                            decision.message(), 0);
                    continue;
                }
                RunContext context = new RunContext(rule.id(), rule.ruleCode(), rule.name(),
                        rule.versionNo(), object, recordId, new LinkedHashMap<>(record),
                        new LinkedHashMap<>(record), rule.triggerType(), "SCHEDULED", 0,
                        RuleModel.Mode.LIVE);
                RecursionGuard.push(context.frame());
                RuleModel.ExecutionTrace trace;
                try {
                    trace = engine.run(context, rule.definition());
                } finally {
                    RecursionGuard.pop();
                }
                log.record(trace);
                executions++;
                actions += trace.actionsExecuted();
            }
            markFired(rule.id(), spec);
            outcomes.add(new SweepOutcome(rule.ruleCode(), spec == null ? "UNSET" : spec.mode(),
                    subjects.size(), executions, actions, describe(spec)));
        }
        return new SweepResult(Instant.now(), scheduled.size(), fired, outcomes);
    }

    /** Which records a relative-to-date rule would act on right now, read-only. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> due(UUID ruleId) {
        AutomationAccess.requireRead();
        RuleDefinitionService.RuleView rule = rules.get(ruleId);
        RuleModel.ScheduleSpec spec = rule.definition().trigger().schedule();
        RuleDefinitionService.ActiveRule active = new RuleDefinitionService.ActiveRule(
                rule.id(), rule.ruleCode(), rule.name(), rule.objectType(), rule.triggerType(),
                rule.activeVersionNo(), rule.definition());
        return matchingRecords(active, spec).stream()
                .map(AutomationRecordService::stringify).toList();
    }

    // ------------------------------------------------------------------ internals

    private List<Map<String, Object>> matchingRecords(RuleDefinitionService.ActiveRule rule,
                                                      RuleModel.ScheduleSpec spec) {
        ObjectMetadataService.ObjectDescriptor object = metadata.describe(rule.objectType());
        if (spec != null && "RELATIVE_TO_FIELD".equalsIgnoreCase(spec.mode())) {
            return metadata.readByDateOffset(object, spec.dateField(),
                    spec.offsetDays() == null ? 0 : spec.offsetDays(), 500);
        }
        // FIXED_TIME and RECURRING sweep the object; the entry condition narrows it,
        // which keeps the "which records" decision in one place — the formula.
        return metadata.sample(rule.objectType(), 200);
    }

    static boolean isDue(RuleModel.ScheduleSpec spec, Instant lastFired) {
        if (spec == null || spec.mode() == null) return false;
        Instant now = Instant.now();
        return switch (spec.mode().toUpperCase(Locale.ROOT)) {
            case "FIXED_TIME" -> {
                if (lastFired != null) yield false;
                try {
                    yield spec.runAt() == null || !Instant.parse(spec.runAt()).isAfter(now);
                } catch (RuntimeException ex) {
                    yield false;
                }
            }
            case "RECURRING" -> {
                int minutes = spec.everyMinutes() == null ? 60 : Math.max(1, spec.everyMinutes());
                yield lastFired == null || !lastFired.plus(minutes, ChronoUnit.MINUTES).isAfter(now);
            }
            // A date-relative sweep is idempotent per day: the window is a calendar
            // day, so re-running it inside the same day would double every action.
            case "RELATIVE_TO_FIELD" -> lastFired == null
                    || lastFired.isBefore(now.truncatedTo(ChronoUnit.DAYS));
            default -> false;
        };
    }

    private Map<UUID, Instant[]> scheduleState() {
        Map<UUID, Instant[]> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                select rule_id, last_fired_at, next_due_at from automation.rule_schedule_state
                where tenant_id = ?
                """, TenantContext.get().tenantId()).forEach(row -> out.put((UUID) row.get("rule_id"),
                new Instant[]{
                        row.get("last_fired_at") == null ? null
                                : ((java.sql.Timestamp) row.get("last_fired_at")).toInstant(),
                        row.get("next_due_at") == null ? null
                                : ((java.sql.Timestamp) row.get("next_due_at")).toInstant()}));
        return out;
    }

    private void markFired(UUID ruleId, RuleModel.ScheduleSpec spec) {
        Instant next = null;
        if (spec != null && "RECURRING".equalsIgnoreCase(spec.mode())) {
            next = Instant.now().plus(spec.everyMinutes() == null ? 60 : spec.everyMinutes(),
                    ChronoUnit.MINUTES);
        } else if (spec != null && "RELATIVE_TO_FIELD".equalsIgnoreCase(spec.mode())) {
            next = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS);
        }
        jdbc.update("""
                insert into automation.rule_schedule_state (tenant_id, rule_id, last_fired_at, next_due_at)
                values (?, ?, now(), ?)
                on conflict (tenant_id, rule_id) do update
                  set last_fired_at = now(), next_due_at = excluded.next_due_at
                """, TenantContext.get().tenantId(), ruleId,
                next == null ? null : java.sql.Timestamp.from(next));
    }
}
