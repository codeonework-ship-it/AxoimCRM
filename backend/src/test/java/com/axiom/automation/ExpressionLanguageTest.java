package com.axiom.automation;

import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** FR-AUT-009: the formula language, its syntax checker and its test evaluator. */
class ExpressionLanguageTest {

    private ExpressionService service;

    @BeforeEach void setUp() {
        ObjectMetadataService metadata = mock(ObjectMetadataService.class);
        when(metadata.describe("OPPORTUNITY")).thenReturn(new ObjectMetadataService.ObjectDescriptor(
                UUID.randomUUID(), "OPPORTUNITY", "Opportunity", "sales", "opportunity", "id",
                "owner_id", null, java.util.List.of("id", "tenant_id"), null, null,
                Map.of("id", "uuid", "name", "text", "amount", "numeric", "close_date", "date",
                        "forecast_category", "text", "next_step", "text")));
        service = new ExpressionService(metadata);
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    private static ExpressionEvaluator.Context ctx(Map<String, Object> newValues,
                                                   Map<String, Object> oldValues) {
        return new ExpressionEvaluator.Context(newValues, oldValues, oldValues.isEmpty(), Map.of());
    }

    // ------------------------------------------------------------------ syntax checker

    @Test void syntaxCheckerRejectsABadFormulaWithItsPosition() {
        ExpressionService.CheckResult result =
                service.check(new ExpressionService.CheckRequest("amount > * 3", "OPPORTUNITY"));

        assertFalse(result.valid());
        assertNotNull(result.position());
        // "amount > * 3" — the offending '*' is the 10th character.
        assertEquals(10, result.position());
        assertTrue(result.message().contains("position 10"), result.message());
        assertEquals("         ^", result.pointer());
    }

    @Test void syntaxCheckerReportsAnUnclosedParenthesisAtTheRightPlace() {
        ExpressionService.CheckResult result =
                service.check(new ExpressionService.CheckRequest("UPPER(name", "OPPORTUNITY"));
        assertFalse(result.valid());
        assertEquals(11, result.position());
        assertTrue(result.message().contains("UPPER"), result.message());
    }

    @Test void syntaxCheckerRejectsAChainedComparison() {
        ExpressionService.CheckResult result =
                service.check(new ExpressionService.CheckRequest("1 < 2 < 3", "OPPORTUNITY"));
        assertFalse(result.valid());
        assertTrue(result.message().contains("cannot be chained"), result.message());
    }

    @Test void syntaxCheckerAcceptsAValidFormulaAndListsTheFieldsItReads() {
        ExpressionService.CheckResult result = service.check(new ExpressionService.CheckRequest(
                "ISCHANGED(amount) AND NEW.amount > 500000", "OPPORTUNITY"));
        assertTrue(result.valid(), result.message());
        assertTrue(result.referencedFields().contains("amount"));
    }

    @Test void syntaxCheckerRejectsAFieldTheObjectDoesNotHave() {
        ExpressionService.CheckResult result =
                service.check(new ExpressionService.CheckRequest("NEW.nonsense > 1", "OPPORTUNITY"));
        assertFalse(result.valid());
        assertTrue(result.unknownFields().contains("nonsense"));
    }

    // ------------------------------------------------------------------ test evaluator

    @Test void testEvaluatorReturnsAValue() {
        ExpressionService.EvaluateResult result = service.evaluate(new ExpressionService.EvaluateRequest(
                "CONCAT('Deal: ', NEW.name, ' at ', TEXT(NEW.amount))", "OPPORTUNITY", null,
                Map.of("name", "Northbrook", "amount", new BigDecimal("540000")), Map.of()));

        assertTrue(result.ok(), result.message());
        assertEquals("Deal: Northbrook at 540000", result.value());
    }

    @Test void testEvaluatorReportsAnEvaluationFailureWithoutThrowing() {
        ExpressionService.EvaluateResult result = service.evaluate(new ExpressionService.EvaluateRequest(
                "NEW.missing + 1", "OPPORTUNITY", null, Map.of("name", "x"), Map.of()));
        assertFalse(result.ok());
        assertTrue(result.message().contains("not a field"), result.message());
    }

    // ------------------------------------------------------------------ old AND new values

    @Test void entryConditionSeesBothOldAndNewValues() {
        Map<String, Object> before = new LinkedHashMap<>(Map.of("amount", new BigDecimal("100000")));
        Map<String, Object> after = new LinkedHashMap<>(Map.of("amount", new BigDecimal("600000")));

        assertTrue(ExpressionEvaluator.condition(
                "OLD.amount < 500000 AND NEW.amount > 500000", ctx(after, before)));
        assertTrue(ExpressionEvaluator.condition("ISCHANGED(amount)", ctx(after, before)));
        assertEquals(new BigDecimal("100000"),
                ExpressionEvaluator.evaluate("PRIORVALUE(amount)", ctx(after, before)));
    }

    @Test void isChangedIsFalseOnACreateBecauseNothingChanged() {
        Map<String, Object> after = Map.of("amount", new BigDecimal("600000"));
        assertFalse(ExpressionEvaluator.condition("ISCHANGED(amount)", ctx(after, Map.of())));
        assertTrue(ExpressionEvaluator.condition("ISNEW()", ctx(after, Map.of())));
    }

    @Test void isChangedIsFalseWhenTheValueWasWrittenButNotAltered() {
        Map<String, Object> same = Map.of("amount", new BigDecimal("600000"));
        assertFalse(ExpressionEvaluator.condition("ISCHANGED(amount)", ctx(same, same)));
    }

    // ------------------------------------------------------------------ function families

    @Test void textNumberDateAndLogicalFunctionsAllEvaluate() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", " northbrook ");
        record.put("amount", new BigDecimal("540000.4"));
        record.put("close_date", LocalDate.of(2026, 7, 30));
        ExpressionEvaluator.Context c = ctx(record, Map.of());

        assertEquals("NORTHBROOK", ExpressionEvaluator.evaluate("UPPER(TRIM(name))", c));
        assertEquals(new BigDecimal("540000"), ExpressionEvaluator.evaluate("ROUND(amount, 0)", c));
        assertEquals(BigDecimal.valueOf(2026), ExpressionEvaluator.evaluate("YEAR(close_date)", c));
        assertEquals("big", ExpressionEvaluator.evaluate("IF(amount > 1000, 'big', 'small')", c));
        assertEquals("commit", ExpressionEvaluator.evaluate(
                "CASE('A', 'A', 'commit', 'B', 'best', 'pipeline')", c));
        assertTrue((Boolean) ExpressionEvaluator.evaluate("NOT(ISBLANK(name))", c));
        assertEquals(BigDecimal.valueOf(3),
                ExpressionEvaluator.evaluate("DAYS_BETWEEN(close_date, ADDDAYS(close_date, 3))", c));
    }

    @Test void aConditionMustProduceTrueOrFalse() {
        assertThrows(ExpressionEvaluator.EvaluationException.class,
                () -> ExpressionEvaluator.condition("1 + 1", ctx(Map.of(), Map.of())));
    }

    @Test void parserRefusesAnythingThatIsNotARecordScope() {
        ExpressionSyntaxException ex = assertThrows(ExpressionSyntaxException.class,
                () -> ExpressionParser.parse("SOMETHING.amount > 1"));
        assertTrue(ex.getMessage().contains("NEW.field or OLD.field"), ex.getMessage());
    }
}
