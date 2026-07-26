package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/** FR-AUT-004: refuse an undefined transition, naming the unsatisfied condition. */
class ProcessServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID PROCESS = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private ProcessService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new ProcessService(jdbc, new ObjectMapper(), mock(ObjectMetadataService.class),
                mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "TENANT_ADMIN",
                "Admin", "admin@example.com"));
        stubModel();
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private void stub(String fragment, List<?> result) {
        doReturn(result).when(jdbc).query(contains(fragment), any(RowMapper.class),
                any(Object[].class));
    }

    private void stubModel() {
        stub("select id from automation.process_definition", List.of(PROCESS));
        stub("select id, process_code, name, object_type, state_field, status, updated_at",
                List.of(new ProcessService.ProcessView(PROCESS, "PRC-OPP-FORECAST",
                        "Opportunity forecast discipline", "OPPORTUNITY", "forecast_category",
                        "ACTIVE", Instant.now(), List.of(), List.of())));
        stub("from automation.process_state", List.of(
                new ProcessService.StateView(UUID.randomUUID(), "PIPELINE", "Pipeline", 10, true,
                        false, List.of(), null),
                new ProcessService.StateView(UUID.randomUUID(), "BEST_CASE", "Best case", 20, false,
                        false, List.of("next_step"), 10080),
                new ProcessService.StateView(UUID.randomUUID(), "COMMIT", "Commit", 30, false, false,
                        List.of("next_step", "close_date"), 4320),
                new ProcessService.StateView(UUID.randomUUID(), "CLOSED", "Closed", 40, false, true,
                        List.of(), null)));
        stub("from automation.process_transition", List.of(
                new ProcessService.TransitionView(UUID.randomUUID(), "PIPELINE", "BEST_CASE",
                        "Promote to best case", List.of(), null),
                new ProcessService.TransitionView(UUID.randomUUID(), "BEST_CASE", "COMMIT",
                        "Commit the deal",
                        List.of(new ProcessService.TransitionCondition("amount", "GTE", "100000",
                                        "an amount of at least 100,000"),
                                new ProcessService.TransitionCondition("close_date", "NOT_BLANK", "",
                                        "a close date")), null),
                new ProcessService.TransitionView(UUID.randomUUID(), "COMMIT", "CLOSED",
                        "Close the deal", List.of(), null)));
    }

    private static Map<String, Object> record(String state, Object amount, Object closeDate,
                                              String nextStep) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("forecast_category", state);
        record.put("amount", amount);
        record.put("close_date", closeDate);
        record.put("next_step", nextStep);
        return record;
    }

    @Test void anUndefinedTransitionIsRefusedAndNamesWhatIsPermitted() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.assertTransitionPermitted("OPPORTUNITY", "PIPELINE", "CLOSED",
                        record("CLOSED", new BigDecimal("540000"), "2026-07-30", "Review")));

        assertTrue(ex.getMessage().contains("defines no transition from \"PIPELINE\" to \"CLOSED\""),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("Permitted from \"PIPELINE\": BEST_CASE"), ex.getMessage());
    }

    @Test void aTransitionToAStateTheProcessDoesNotDefineIsRefused() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.assertTransitionPermitted("OPPORTUNITY", "PIPELINE", "OMITTED",
                        record("OMITTED", new BigDecimal("1"), "2026-07-30", "x")));
        assertTrue(ex.getMessage().contains("has no state \"OMITTED\""), ex.getMessage());
    }

    @Test void aDefinedTransitionWhoseConditionFailsIsRefusedNamingTheCondition() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.assertTransitionPermitted("OPPORTUNITY", "BEST_CASE", "COMMIT",
                        record("COMMIT", new BigDecimal("50000"), "2026-07-30", "Review")));

        assertTrue(ex.getMessage().contains("Unsatisfied condition: amount GTE 100000"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("actual: 50000"), ex.getMessage());
        assertTrue(ex.getMessage().contains("an amount of at least 100,000"), ex.getMessage());
    }

    @Test void aPerStateMandatoryFieldIsEnforced() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.assertTransitionPermitted("OPPORTUNITY", "BEST_CASE", "COMMIT",
                        record("COMMIT", new BigDecimal("540000"), "2026-07-30", "  ")));

        assertTrue(ex.getMessage().contains("requires \"next_step\" to be set"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Unsatisfied condition: next_step is mandatory in COMMIT"),
                ex.getMessage());
    }

    @Test void aTransitionThatSatisfiesEverythingIsPermitted() {
        assertDoesNotThrow(() -> service.assertTransitionPermitted("OPPORTUNITY", "BEST_CASE",
                "COMMIT", record("COMMIT", new BigDecimal("540000"), "2026-07-30", "Legal review")));
    }

    @Test void withNoActiveProcessNothingIsRefused() {
        stub("select id from automation.process_definition", List.of());
        assertDoesNotThrow(() -> service.assertTransitionPermitted("OPPORTUNITY", "ANYTHING",
                "ANYTHING_ELSE", Map.of()));
    }

    @Test void theJavaConditionOperatorsMatchTheDatabaseFunction() {
        assertTrue(ProcessService.conditionHolds("5", "GTE", "5"));
        assertTrue(ProcessService.conditionHolds("6", "GT", "5"));
        assertTrue(ProcessService.conditionHolds("x", "NOT_BLANK", ""));
        assertTrue(ProcessService.conditionHolds(null, "BLANK", ""));
        assertTrue(ProcessService.conditionHolds("B", "IN", "A|B|C"));
        assertTrue(ProcessService.conditionHolds("true", "IS_TRUE", ""));
        // A malformed comparison is an unsatisfied condition, never a satisfied one.
        assertTrue(!ProcessService.conditionHolds("not-a-number", "GT", "5"));
        assertTrue(!ProcessService.conditionHolds("5", "WAT", "5"));
    }
}
