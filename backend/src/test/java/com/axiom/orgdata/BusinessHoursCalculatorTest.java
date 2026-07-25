package com.axiom.orgdata;

import com.axiom.common.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-MDM-005 — the SLA clock: business hours only, holidays excluded. */
class BusinessHoursCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** Monday to Friday, 09:00-18:00. */
    private static Map<Integer, BusinessHoursCalculator.OpenWindow> weekdays() {
        Map<Integer, BusinessHoursCalculator.OpenWindow> weekly = new LinkedHashMap<>();
        for (int day = 1; day <= 5; day++) {
            weekly.put(day, new BusinessHoursCalculator.OpenWindow(
                    LocalTime.of(9, 0), LocalTime.of(18, 0)));
        }
        return weekly;
    }

    private static ZonedDateTime at(int year, Month month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month.getValue(), day, hour, minute, 0, 0, ZONE);
    }

    @Test void fridayEveningSlaRollsIntoMondayMorning() {
        // US-E03-03: assigned 17:55 Friday, hours close at 18:00, 30-minute target.
        // Five minutes are consumed on Friday; the remaining 25 fall on Monday.
        ZonedDateTime due = BusinessHoursCalculator.dueAt(
                at(2026, Month.JULY, 24, 17, 55), ZONE, weekdays(), Set.of(), Duration.ofMinutes(30));

        assertEquals(at(2026, Month.JULY, 27, 9, 25), due);
    }

    @Test void holidayIsExcludedFromTheSlaClock() {
        // Monday 27 July 2026 is a holiday, so the remaining 25 minutes land on Tuesday.
        ZonedDateTime due = BusinessHoursCalculator.dueAt(
                at(2026, Month.JULY, 24, 17, 55), ZONE, weekdays(),
                Set.of(LocalDate.of(2026, 7, 27)), Duration.ofMinutes(30));

        assertEquals(at(2026, Month.JULY, 28, 9, 25), due);
    }

    @Test void consecutiveHolidaysAreAllSkipped() {
        ZonedDateTime due = BusinessHoursCalculator.dueAt(
                at(2026, Month.JULY, 24, 17, 55), ZONE, weekdays(),
                Set.of(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 28)),
                Duration.ofMinutes(30));

        assertEquals(at(2026, Month.JULY, 29, 9, 25), due);
    }

    @Test void clockStartsAtNextOpeningWhenWorkArrivesOutOfHours() {
        // Arriving at 22:00 on Wednesday consumes nothing overnight.
        ZonedDateTime due = BusinessHoursCalculator.dueAt(
                at(2026, Month.JULY, 22, 22, 0), ZONE, weekdays(), Set.of(), Duration.ofMinutes(45));

        assertEquals(at(2026, Month.JULY, 23, 9, 45), due);
    }

    @Test void weekendArrivalWaitsForMonday() {
        ZonedDateTime due = BusinessHoursCalculator.dueAt(
                at(2026, Month.JULY, 25, 11, 0), ZONE, weekdays(), Set.of(), Duration.ofHours(2));

        assertEquals(at(2026, Month.JULY, 27, 11, 0), due);
    }

    @Test void aTargetLongerThanOneDaySpansMultipleBusinessDays() {
        // Nine open hours a day; a 14-hour target consumes Monday plus five hours of Tuesday.
        ZonedDateTime due = BusinessHoursCalculator.dueAt(
                at(2026, Month.JULY, 27, 9, 0), ZONE, weekdays(), Set.of(), Duration.ofHours(14));

        assertEquals(at(2026, Month.JULY, 28, 14, 0), due);
    }

    @Test void businessSecondsBetweenCountsOnlyOpenTime() {
        long seconds = BusinessHoursCalculator.businessSecondsBetween(
                at(2026, Month.JULY, 24, 17, 55), at(2026, Month.JULY, 27, 9, 25),
                ZONE, weekdays(), Set.of());

        assertEquals(Duration.ofMinutes(30).toSeconds(), seconds);
    }

    @Test void definitionWithNoOpenDaysIsRefusedWithAnActionableMessage() {
        ConflictException ex = assertThrows(ConflictException.class, () ->
                BusinessHoursCalculator.dueAt(at(2026, Month.JULY, 24, 10, 0), ZONE,
                        Map.of(), Set.of(), Duration.ofMinutes(30)));
        assertTrue(ex.getMessage().contains("Add at least one working day"), ex.getMessage());
    }
}
