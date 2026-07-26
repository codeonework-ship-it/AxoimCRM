package com.axiom.automation;

import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-AUT-012: a cascade is detected and HALTED, and the diagnostic names the
 * participating rules and the cycle.
 */
class RecursionProtectionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();

    private static final ObjectMetadataService.ObjectDescriptor OPPORTUNITY =
            new ObjectMetadataService.ObjectDescriptor(UUID.randomUUID(), "OPPORTUNITY", "Opportunity",
                    "sales", "opportunity", "id", "owner_id", null, List.of("id", "tenant_id"),
                    null, null, Map.of("id", "uuid", "next_step", "text", "forecast_category", "text"));

    @BeforeEach void setUp() {
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "TENANT_ADMIN",
                "Admin", "admin@example.com"));
        RecursionGuard.clear();
    }

    @AfterEach void tearDown() {
        RecursionGuard.clear();
        TenantContext.clear();
    }

    private static RecursionGuard.Frame frame(String code, String name) {
        return new RecursionGuard.Frame(code, name, "OPPORTUNITY", RECORD);
    }

    @Test void aPairOfRulesThatFeedEachOtherIsDetectedAndTheCycleIsNamed() {
        RecursionGuard.push(frame("AUT-LOOP-ALPHA", "Cascade demonstration A"));
        RecursionGuard.push(frame("AUT-LOOP-BETA", "Cascade demonstration B"));

        Optional<String> diagnostic =
                RecursionGuard.cycleIfEntered(frame("AUT-LOOP-ALPHA", "Cascade demonstration A"));

        assertTrue(diagnostic.isPresent(), "re-entering ALPHA on the same record is a cycle");
        String message = diagnostic.get();
        assertTrue(message.contains("AUT-LOOP-ALPHA → AUT-LOOP-BETA → AUT-LOOP-ALPHA"), message);
        assertTrue(message.contains("Cascade demonstration A"), message);
        assertTrue(message.contains("Cascade demonstration B"), message);
        assertTrue(message.contains(RECORD.toString()), message);
        assertTrue(message.contains("halted"), message);
    }

    @Test void adifferentRecordIsNotACycle() {
        RecursionGuard.push(frame("AUT-LOOP-ALPHA", "A"));
        Optional<String> diagnostic = RecursionGuard.cycleIfEntered(
                new RecursionGuard.Frame("AUT-LOOP-ALPHA", "A", "OPPORTUNITY", UUID.randomUUID()));
        assertTrue(diagnostic.isEmpty(), "the same rule on a different record is fan-out, not recursion");
    }

    @Test void theGuardUnwindsSoASecondDispatchIsNotFalselyFlagged() {
        RecursionGuard.push(frame("AUT-A", "A"));
        RecursionGuard.pop();
        assertEquals(0, RecursionGuard.depth());
        assertTrue(RecursionGuard.cycleIfEntered(frame("AUT-A", "A")).isEmpty());
    }

    /** End to end through the dispatcher: the cascade is halted and logged, not thrown. */
    @Test void theDispatcherRecordsAHaltedExecutionNamingTheCycle() {
        RuleDefinitionService rules = mock(RuleDefinitionService.class);
        RuleEngine engine = mock(RuleEngine.class);
        ObjectMetadataService metadata = mock(ObjectMetadataService.class);
        ExecutionLogService log = mock(ExecutionLogService.class);
        ThrottleService throttle = mock(ThrottleService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        when(metadata.describe("OPPORTUNITY")).thenReturn(OPPORTUNITY);
        when(throttle.policy()).thenReturn(new ThrottleService.Policy(60, 2000, 8));
        when(rules.activeRecordChangeRules(anyString(), anyString())).thenReturn(List.of(
                new RuleDefinitionService.ActiveRule(UUID.randomUUID(), "AUT-LOOP-ALPHA",
                        "Cascade demonstration A", "OPPORTUNITY", "RECORD_CHANGE", 1,
                        new RuleModel.Definition(null, null, List.of()))));
        when(log.record(any())).thenReturn(UUID.randomUUID());

        RecordChangeDispatcher dispatcher =
                new RecordChangeDispatcher(rules, engine, metadata, log, throttle);

        // Simulate being mid-cascade: ALPHA is already active on this record.
        RecursionGuard.push(frame("AUT-LOOP-ALPHA", "Cascade demonstration A"));

        RecordChangeDispatcher.DispatchResult result =
                dispatcher.dispatch("OPPORTUNITY", RECORD, "UPDATE", Map.of(), Map.of(), 1);

        assertTrue(result.halted());
        assertTrue(result.haltDiagnostic().contains("AUT-LOOP-ALPHA → AUT-LOOP-ALPHA"),
                result.haltDiagnostic());
        assertEquals("HALTED", result.executions().getFirst().status());
        // The engine was never entered: halting means not running, not running and undoing.
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.never()).run(any(), any());
        // And the fair-use budget was not charged for a cycle.
        org.mockito.Mockito.verify(throttle, org.mockito.Mockito.never()).acquire();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test void aCascadeDeeperThanThePolicyIsHaltedNamingTheParticipants() {
        RuleDefinitionService rules = mock(RuleDefinitionService.class);
        RuleEngine engine = mock(RuleEngine.class);
        ObjectMetadataService metadata = mock(ObjectMetadataService.class);
        ExecutionLogService log = mock(ExecutionLogService.class);
        ThrottleService throttle = mock(ThrottleService.class);

        when(metadata.describe("OPPORTUNITY")).thenReturn(OPPORTUNITY);
        when(throttle.policy()).thenReturn(new ThrottleService.Policy(60, 2000, 2));
        when(rules.activeRecordChangeRules(anyString(), anyString())).thenReturn(List.of(
                new RuleDefinitionService.ActiveRule(UUID.randomUUID(), "AUT-DEEP", "Deep rule",
                        "OPPORTUNITY", "RECORD_CHANGE", 1,
                        new RuleModel.Definition(null, null, List.of()))));
        when(log.record(any())).thenReturn(UUID.randomUUID());

        RecordChangeDispatcher dispatcher =
                new RecordChangeDispatcher(rules, engine, metadata, log, throttle);
        RecordChangeDispatcher.DispatchResult result =
                dispatcher.dispatch("OPPORTUNITY", RECORD, "UPDATE", Map.of(), Map.of(), 9);

        assertTrue(result.halted());
        assertTrue(result.haltDiagnostic().contains("depth 9"), result.haltDiagnostic());
        assertTrue(result.haltDiagnostic().contains("AUT-DEEP (Deep rule)"), result.haltDiagnostic());
    }

    @Test void aThrottledTenantIsRateLimitedNotRuleLimited() {
        RuleDefinitionService rules = mock(RuleDefinitionService.class);
        RuleEngine engine = mock(RuleEngine.class);
        ObjectMetadataService metadata = mock(ObjectMetadataService.class);
        ExecutionLogService log = mock(ExecutionLogService.class);
        ThrottleService throttle = mock(ThrottleService.class);

        when(metadata.describe("OPPORTUNITY")).thenReturn(OPPORTUNITY);
        when(throttle.policy()).thenReturn(new ThrottleService.Policy(60, 1, 8));
        when(throttle.acquire()).thenReturn(new ThrottleService.Decision(false, 2, 1, 60,
                "Automation is being fair-use throttled: this is a rate limit, not a rule limit"));
        when(rules.activeRecordChangeRules(anyString(), anyString())).thenReturn(List.of(
                new RuleDefinitionService.ActiveRule(UUID.randomUUID(), "AUT-X", "X", "OPPORTUNITY",
                        "RECORD_CHANGE", 1, new RuleModel.Definition(null, null, List.of()))));
        when(log.recordThrottled(any(), anyString(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(UUID.randomUUID());

        RecordChangeDispatcher dispatcher =
                new RecordChangeDispatcher(rules, engine, metadata, log, throttle);
        RecordChangeDispatcher.DispatchResult result =
                dispatcher.dispatch("OPPORTUNITY", RECORD, "UPDATE", Map.of(), Map.of(), 0);

        assertEquals("THROTTLED", result.executions().getFirst().status());
        assertTrue(result.executions().getFirst().detail().contains("not a rule limit"));
    }
}
