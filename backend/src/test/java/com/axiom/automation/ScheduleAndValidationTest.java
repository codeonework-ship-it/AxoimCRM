package com.axiom.automation;

import com.axiom.audit.AuditService;
import com.axiom.common.BulkValidationException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FR-AUT-002 scheduled automation and FR-AUT-005 validation rules. */
class ScheduleAndValidationTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RULE = UUID.randomUUID();

    private static final ObjectMetadataService.ObjectDescriptor OPPORTUNITY =
            new ObjectMetadataService.ObjectDescriptor(UUID.randomUUID(), "OPPORTUNITY", "Opportunity",
                    "sales", "opportunity", "id", "owner_id", null, List.of("id"), null, null,
                    Map.of("id", "uuid", "amount", "numeric", "close_date", "date",
                            "is_closed", "boolean", "name", "text"));

    @BeforeEach void setUp() {
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "TENANT_ADMIN",
                "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ FR-AUT-002

    @Test void aScheduleRelativeToARecordDateFieldQueriesThatFieldWithThatOffset() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RuleDefinitionService rules = mock(RuleDefinitionService.class);
        ObjectMetadataService metadata = mock(ObjectMetadataService.class);

        RuleModel.ScheduleSpec spec = new RuleModel.ScheduleSpec("RELATIVE_TO_FIELD", null, null,
                "close_date", -30, "08:00");
        RuleModel.Definition definition = new RuleModel.Definition(
                new RuleModel.TriggerSpec("SCHEDULED", List.of(), spec), "NEW.is_closed = false",
                List.of());

        when(rules.get(RULE)).thenReturn(new RuleDefinitionService.RuleView(RULE, "AUT-RENEWAL-SWEEP",
                "Renewal sweep", null, "OPPORTUNITY", "SCHEDULED", "ACTIVE", 1, 100,
                Instant.now(), Instant.now(), 1, definition));
        when(metadata.describe("OPPORTUNITY")).thenReturn(OPPORTUNITY);
        when(metadata.readByDateOffset(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "name", "Northbrook")));

        ScheduleService service = new ScheduleService(jdbc, rules, mock(RuleEngine.class), metadata,
                mock(ExecutionLogService.class), mock(ThrottleService.class));

        List<Map<String, Object>> due = service.due(RULE);

        assertEquals(1, due.size());
        // The window is thirty days BEFORE the record's close_date, expressed as an
        // offset against the column rather than a materialised per-record due date.
        verify(metadata).readByDateOffset(eq(OPPORTUNITY), eq("close_date"), eq(-30), anyInt());
    }

    @Test void dueTestsForTheThreeScheduleShapes() {
        RuleModel.ScheduleSpec fixed = new RuleModel.ScheduleSpec("FIXED_TIME",
                Instant.now().minus(1, ChronoUnit.HOURS).toString(), null, null, null, null);
        assertTrue(ScheduleService.isDue(fixed, null), "a past fixed time that never fired is due");
        assertFalse(ScheduleService.isDue(fixed, Instant.now().minus(2, ChronoUnit.HOURS)),
                "a fixed time fires once, ever");

        RuleModel.ScheduleSpec future = new RuleModel.ScheduleSpec("FIXED_TIME",
                Instant.now().plus(1, ChronoUnit.HOURS).toString(), null, null, null, null);
        assertFalse(ScheduleService.isDue(future, null));

        RuleModel.ScheduleSpec recurring =
                new RuleModel.ScheduleSpec("RECURRING", null, 60, null, null, null);
        assertTrue(ScheduleService.isDue(recurring, Instant.now().minus(61, ChronoUnit.MINUTES)));
        assertFalse(ScheduleService.isDue(recurring, Instant.now().minus(5, ChronoUnit.MINUTES)));

        RuleModel.ScheduleSpec relative =
                new RuleModel.ScheduleSpec("RELATIVE_TO_FIELD", null, null, "close_date", -30, "08:00");
        assertTrue(ScheduleService.isDue(relative, null));
        assertFalse(ScheduleService.isDue(relative, Instant.now()),
                "a date-relative sweep runs once per calendar day, or it doubles every action");

        assertFalse(ScheduleService.isDue(null, null));
    }

    // ------------------------------------------------------------------ FR-AUT-005

    @Test void aValidationRuleRefusesWithItsOwnMessageAndTargetField() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMetadataService metadata = mock(ObjectMetadataService.class);
        ValidationRuleService service = new ValidationRuleService(jdbc, metadata,
                mock(AuditService.class));

        doReturn(List.of(new ValidationRuleService.ValidationRuleView(UUID.randomUUID(),
                "VAL-OPP-NEGATIVE-AMOUNT", "Amount cannot be negative", "OPPORTUNITY",
                "NEW.amount < 0", "The opportunity amount cannot be negative.", "amount", true)))
                .when(jdbc).query(contains("from automation.validation_rule"), any(RowMapper.class),
                        any(Object[].class));

        BulkValidationException ex = assertThrows(BulkValidationException.class,
                () -> service.assertValid("OPPORTUNITY",
                        Map.of("amount", new BigDecimal("-5")), Map.of()));

        assertEquals("The opportunity amount cannot be negative.", ex.getMessage());
        assertEquals(List.of("amount: The opportunity amount cannot be negative. "
                + "[VAL-OPP-NEGATIVE-AMOUNT]"), ex.details());
    }

    @Test void aValidRecordPassesAndAnUnevaluableRuleFailsClosed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ValidationRuleService service = new ValidationRuleService(jdbc,
                mock(ObjectMetadataService.class), mock(AuditService.class));

        doReturn(List.of(new ValidationRuleService.ValidationRuleView(UUID.randomUUID(), "VAL-A",
                "A", "OPPORTUNITY", "NEW.amount < 0", "negative", "amount", true)))
                .when(jdbc).query(contains("from automation.validation_rule"), any(RowMapper.class),
                        any(Object[].class));
        assertTrue(service.evaluate("OPPORTUNITY", Map.of("amount", new BigDecimal("5")), Map.of())
                .isEmpty());

        doReturn(List.of(new ValidationRuleService.ValidationRuleView(UUID.randomUUID(), "VAL-B",
                "B", "OPPORTUNITY", "NEW.nonexistent < 0", "nope", "amount", true)))
                .when(jdbc).query(contains("from automation.validation_rule"), any(RowMapper.class),
                        any(Object[].class));
        List<ValidationRuleService.ValidationFailure> failures =
                service.evaluate("OPPORTUNITY", Map.of("amount", new BigDecimal("5")), Map.of());
        assertEquals(1, failures.size(), "a control that cannot be evaluated must not pass");
        assertTrue(failures.getFirst().message().contains("could not be evaluated"));
    }

    // ------------------------------------------------------------------ FR-AUT-014 telemetry

    @Test void telemetryStatesThereIsNoRuleCountLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ThrottleService service = new ThrottleService(jdbc, mock(AuditService.class));

        doReturn(List.of(new ThrottleService.Policy(60, 2000, 8))).when(jdbc)
                .query(contains("from automation.throttle_policy"), any(RowMapper.class),
                        any(Object[].class));
        doReturn(List.<ThrottleService.WindowSample>of()).when(jdbc)
                .query(contains("from automation.throttle_window"), any(RowMapper.class),
                        any(Object[].class));
        when(jdbc.queryForObject(contains("count(*) from automation.rule_definition"),
                eq(Integer.class), any(Object[].class))).thenReturn(742);

        ThrottleService.Telemetry telemetry = service.telemetry();

        assertEquals(742, telemetry.rulesDefined());
        assertEquals(null, telemetry.ruleCountLimit(), "there is no cap, and the payload says so");
        assertTrue(telemetry.resourceProtection().contains("no limit on the number of rules"));
    }

    @Test void theThrottleWindowIsAlignedToItsBoundary() {
        Instant a = ThrottleService.windowStart(60);
        assertEquals(0, a.getEpochSecond() % 60);
        Instant b = ThrottleService.windowStart(300);
        assertEquals(0, b.getEpochSecond() % 300);
    }
}
