package com.axiom.orgdata;

import com.axiom.common.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-MDM-004 — fiscal period arithmetic, including 4-4-5 boundaries. */
class FiscalPeriodPlannerTest {

    private static FiscalPeriodPlanner.PlannedPeriod period(
            FiscalPeriodPlanner.PlannedYear year, String type, int number) {
        return year.periods().stream()
                .filter(p -> p.type().equals(type))
                .filter(p -> type.equals("QUARTER")
                        ? p.quarter() == number
                        : p.period() == number)
                .findFirst().orElseThrow();
    }

    @Test void fourFourFivePeriodBoundariesAreFourFourAndFiveWeeks() {
        // Fiscal year starts Monday 5 January 2026.
        FiscalPeriodPlanner.PlannedYear year =
                FiscalPeriodPlanner.plan("FOUR_FOUR_FIVE", "FY2026", LocalDate.of(2026, 1, 5), null, List.of());

        FiscalPeriodPlanner.PlannedPeriod p1 = period(year, "PERIOD", 1);
        FiscalPeriodPlanner.PlannedPeriod p2 = period(year, "PERIOD", 2);
        FiscalPeriodPlanner.PlannedPeriod p3 = period(year, "PERIOD", 3);

        assertEquals(LocalDate.of(2026, 1, 5), p1.startDate());
        assertEquals(LocalDate.of(2026, 2, 1), p1.endDate(), "P1 is four whole weeks");
        assertEquals(LocalDate.of(2026, 2, 2), p2.startDate());
        assertEquals(LocalDate.of(2026, 3, 1), p2.endDate(), "P2 is four whole weeks");
        assertEquals(LocalDate.of(2026, 3, 2), p3.startDate());
        assertEquals(LocalDate.of(2026, 4, 5), p3.endDate(), "P3 is five whole weeks");
    }

    @Test void fourFourFiveQuartersAreThirteenWeeksAndTheYearIs364Days() {
        FiscalPeriodPlanner.PlannedYear year =
                FiscalPeriodPlanner.plan("FOUR_FOUR_FIVE", "FY2026", LocalDate.of(2026, 1, 5), null, List.of());

        FiscalPeriodPlanner.PlannedPeriod q1 = period(year, "QUARTER", 1);
        assertEquals(LocalDate.of(2026, 1, 5), q1.startDate());
        assertEquals(LocalDate.of(2026, 4, 5), q1.endDate());
        assertEquals(91, q1.startDate().datesUntil(q1.endDate().plusDays(1)).count(),
                "a 4-4-5 quarter is exactly thirteen weeks");

        FiscalPeriodPlanner.PlannedPeriod q4 = period(year, "QUARTER", 4);
        assertEquals(LocalDate.of(2027, 1, 3), q4.endDate());
        assertEquals(364, year.startDate().datesUntil(year.endDate().plusDays(1)).count(),
                "a 4-4-5 year is 52 whole weeks");
        assertEquals(16, year.periods().size(), "four quarters plus twelve periods");
    }

    @Test void fourFourFivePeriodsAreContiguousWithNoGapsOrOverlaps() {
        FiscalPeriodPlanner.PlannedYear year =
                FiscalPeriodPlanner.plan("FOUR_FOUR_FIVE", "FY2026", LocalDate.of(2026, 1, 5), null, List.of());
        List<FiscalPeriodPlanner.PlannedPeriod> periods = year.periods().stream()
                .filter(p -> p.type().equals("PERIOD"))
                .sorted((a, b) -> a.period() - b.period()).toList();
        for (int i = 1; i < periods.size(); i++) {
            assertEquals(periods.get(i - 1).endDate().plusDays(1), periods.get(i).startDate(),
                    "period " + (i + 1) + " must start the day after period " + i + " ends");
        }
    }

    @Test void standardCalendarUsesCalendarMonthsFromTheFiscalStart() {
        FiscalPeriodPlanner.PlannedYear year =
                FiscalPeriodPlanner.plan("STANDARD", "FY2027", LocalDate.of(2026, 4, 1), null, List.of());

        assertEquals(LocalDate.of(2027, 3, 31), year.endDate());
        assertEquals(LocalDate.of(2026, 6, 30), period(year, "QUARTER", 1).endDate());
        assertEquals(LocalDate.of(2026, 4, 30), period(year, "PERIOD", 1).endDate());
        assertEquals("FY2027-P01", period(year, "PERIOD", 1).label());
        assertTrue(year.periods().stream().anyMatch(p -> p.label().equals("FY2027-Q4")));
    }

    @Test void customCalendarRefusesOverlappingPeriods() {
        List<FiscalPeriodPlanner.PlannedPeriod> overlapping = List.of(
                new FiscalPeriodPlanner.PlannedPeriod("PERIOD", 1, 1, "H1",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
                new FiscalPeriodPlanner.PlannedPeriod("PERIOD", 2, 2, "H2",
                        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)));

        ConflictException ex = assertThrows(ConflictException.class, () ->
                FiscalPeriodPlanner.plan("CUSTOM", "FY2026", LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31), overlapping));
        assertTrue(ex.getMessage().contains("overlaps"), ex.getMessage());
    }

    @Test void customCalendarRefusesPeriodsOutsideTheFiscalYear() {
        List<FiscalPeriodPlanner.PlannedPeriod> outside = List.of(
                new FiscalPeriodPlanner.PlannedPeriod("PERIOD", 1, 1, "H1",
                        LocalDate.of(2025, 12, 1), LocalDate.of(2026, 6, 30)));

        assertThrows(ConflictException.class, () ->
                FiscalPeriodPlanner.plan("CUSTOM", "FY2026", LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31), outside));
    }
}
