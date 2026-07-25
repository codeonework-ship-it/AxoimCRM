package com.axiom.orgdata;

import com.axiom.common.ConflictException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * FR-MDM-004 — the fiscal-period arithmetic, kept as a pure function.
 *
 * <p>Forecasting, quota and reporting must all resolve a period the same way.
 * The only way to guarantee that is for there to be exactly one implementation
 * of the arithmetic and for it to be a function of its inputs — no database, no
 * clock, no tenant context. That also makes the boundaries directly testable,
 * which matters because off-by-one errors in a 4-4-5 calendar are invisible
 * until a quarter closes on the wrong day.
 */
public final class FiscalPeriodPlanner {

    /** A generated period. {@code quarter} is 1-4; {@code period} is null for quarter rows. */
    public record PlannedPeriod(String type, Integer quarter, Integer period, String label,
                                LocalDate startDate, LocalDate endDate) {}

    public record PlannedYear(String label, LocalDate startDate, LocalDate endDate,
                              List<PlannedPeriod> periods) {}

    /** Week counts per quarter in a 4-4-5 calendar: 13 weeks a quarter, 52 a year. */
    private static final int[] FOUR_FOUR_FIVE_WEEKS = {4, 4, 5};

    private FiscalPeriodPlanner() {}

    /**
     * @param calendarType STANDARD, FOUR_FOUR_FIVE or CUSTOM
     * @param yearLabel    the label the tenant calls this year, e.g. FY2027
     * @param yearStart    the first day of the fiscal year
     * @param customEnd    the last day, required for CUSTOM, ignored otherwise
     */
    public static PlannedYear plan(String calendarType, String yearLabel, LocalDate yearStart,
                                   LocalDate customEnd, List<PlannedPeriod> customPeriods) {
        return switch (calendarType) {
            case "STANDARD" -> planStandard(yearLabel, yearStart);
            case "FOUR_FOUR_FIVE" -> planFourFourFive(yearLabel, yearStart);
            case "CUSTOM" -> planCustom(yearLabel, yearStart, customEnd, customPeriods);
            default -> throw new ConflictException("Calendar type must be STANDARD, CUSTOM or FOUR_FOUR_FIVE.");
        };
    }

    /** Twelve calendar months from the year start; quarters are groups of three. */
    private static PlannedYear planStandard(String yearLabel, LocalDate yearStart) {
        List<PlannedPeriod> periods = new ArrayList<>();
        LocalDate yearEnd = yearStart.plusYears(1).minusDays(1);
        for (int q = 1; q <= 4; q++) {
            LocalDate qStart = yearStart.plusMonths((long) (q - 1) * 3);
            LocalDate qEnd = yearStart.plusMonths((long) q * 3).minusDays(1);
            periods.add(new PlannedPeriod("QUARTER", q, null, yearLabel + "-Q" + q, qStart, qEnd));
        }
        for (int p = 1; p <= 12; p++) {
            LocalDate pStart = yearStart.plusMonths(p - 1L);
            LocalDate pEnd = yearStart.plusMonths(p).minusDays(1);
            periods.add(new PlannedPeriod("PERIOD", ((p - 1) / 3) + 1, p,
                    "%s-P%02d".formatted(yearLabel, p), pStart, pEnd));
        }
        return new PlannedYear(yearLabel, yearStart, yearEnd, periods);
    }

    /**
     * 4-4-5: each quarter is thirteen weeks split 4 / 4 / 5. The year is 364
     * days, which is the whole point — every period is a whole number of weeks,
     * so week-over-week comparison is meaningful.
     */
    private static PlannedYear planFourFourFive(String yearLabel, LocalDate yearStart) {
        List<PlannedPeriod> periods = new ArrayList<>();
        LocalDate cursor = yearStart;
        int periodNumber = 0;
        List<PlannedPeriod> quarters = new ArrayList<>();
        for (int q = 1; q <= 4; q++) {
            LocalDate quarterStart = cursor;
            for (int slot = 0; slot < FOUR_FOUR_FIVE_WEEKS.length; slot++) {
                periodNumber++;
                LocalDate periodStart = cursor;
                LocalDate periodEnd = cursor.plusWeeks(FOUR_FOUR_FIVE_WEEKS[slot]).minusDays(1);
                periods.add(new PlannedPeriod("PERIOD", q, periodNumber,
                        "%s-P%02d".formatted(yearLabel, periodNumber), periodStart, periodEnd));
                cursor = periodEnd.plusDays(1);
            }
            quarters.add(new PlannedPeriod("QUARTER", q, null, yearLabel + "-Q" + q,
                    quarterStart, cursor.minusDays(1)));
        }
        List<PlannedPeriod> all = new ArrayList<>(quarters);
        all.addAll(periods);
        return new PlannedYear(yearLabel, yearStart, cursor.minusDays(1), all);
    }

    private static PlannedYear planCustom(String yearLabel, LocalDate yearStart, LocalDate yearEnd,
                                          List<PlannedPeriod> customPeriods) {
        if (yearEnd == null || !yearEnd.isAfter(yearStart)) {
            throw new ConflictException("A custom fiscal year needs an end date after its start date.");
        }
        if (customPeriods == null || customPeriods.isEmpty()) {
            throw new ConflictException("A custom fiscal calendar needs at least one period. "
                    + "Supply the periods, or choose STANDARD or FOUR_FOUR_FIVE to have them generated.");
        }
        List<PlannedPeriod> sorted = new ArrayList<>(customPeriods);
        sorted.sort((a, b) -> a.startDate().compareTo(b.startDate()));
        LocalDate previousEnd = null;
        for (PlannedPeriod period : sorted) {
            if (period.endDate().isBefore(period.startDate())) {
                throw new ConflictException("Period " + period.label()
                        + " ends before it starts. Correct its dates and retry.");
            }
            if (period.startDate().isBefore(yearStart) || period.endDate().isAfter(yearEnd)) {
                throw new ConflictException("Period " + period.label()
                        + " falls outside the fiscal year " + yearLabel + ". "
                        + "Move it inside " + yearStart + " to " + yearEnd + ".");
            }
            if (previousEnd != null && !period.startDate().isAfter(previousEnd)) {
                throw new ConflictException("Period " + period.label()
                        + " overlaps the period before it. Fiscal periods must not overlap, "
                        + "or a record would belong to two of them at once.");
            }
            previousEnd = period.endDate();
        }
        return new PlannedYear(yearLabel, yearStart, yearEnd, sorted);
    }
}
