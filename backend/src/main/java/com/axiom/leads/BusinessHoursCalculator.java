package com.axiom.leads;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a working calendar to find the instant at which a number of <em>working</em>
 * minutes will have elapsed (FR-LED-009: the response clock pauses outside
 * business hours).
 *
 * <p>Pure function, no database, no clock of its own — which is what makes the
 * business-hours behaviour testable at all. The temptation is to do this in SQL
 * with a generate_series; the resulting query is unreadable, and the one thing
 * this logic must be is verifiable against a holiday and a weekend by hand.
 *
 * <p>Semantics worth being explicit about:
 * <ul>
 *   <li>A clock started <em>before</em> the day opens does not begin until open —
 *       a lead arriving at 03:00 gets its full working allowance from 09:00, not a
 *       deadline that has already half expired.</li>
 *   <li>A clock started after close rolls to the next open day.</li>
 *   <li>Holidays consume no time at all.</li>
 *   <li>With no open days configured the calendar is treated as 24/7 rather than
 *       as "never" — a misconfiguration must not produce a deadline in the year
 *       2400.</li>
 * </ul>
 */
public final class BusinessHoursCalculator {

    /** How far forward the walk will look before giving up. */
    private static final int MAX_DAYS = 366;

    private BusinessHoursCalculator() {}

    /**
     * @param zone     the calendar's time zone
     * @param openDays day-of-week to opening interval; a day absent from the map
     *                 is closed
     * @param holidays dates on which no working time accrues
     */
    public record Calendar(ZoneId zone, Map<DayOfWeek, Interval> openDays, Set<LocalDate> holidays) {

        public Calendar {
            openDays = Map.copyOf(openDays);
            holidays = Set.copyOf(holidays);
        }

        public boolean alwaysOpen() {
            return openDays.isEmpty();
        }
    }

    public record Interval(LocalTime open, LocalTime close) {}

    /**
     * @return the instant by which {@code workingMinutes} of open time will have
     *         elapsed, starting from {@code from}
     */
    public static Instant dueAt(Instant from, int workingMinutes, Calendar calendar) {
        if (workingMinutes <= 0) {
            return from;
        }
        if (calendar.alwaysOpen()) {
            return from.plus(Duration.ofMinutes(workingMinutes));
        }

        ZonedDateTime cursor = from.atZone(calendar.zone());
        long remaining = workingMinutes;

        for (int day = 0; day <= MAX_DAYS; day++) {
            LocalDate date = cursor.toLocalDate();
            Interval interval = calendar.openDays().get(date.getDayOfWeek());
            if (interval == null || calendar.holidays().contains(date)) {
                cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
                continue;
            }
            ZonedDateTime open = date.atTime(interval.open()).atZone(calendar.zone());
            ZonedDateTime close = date.atTime(interval.close()).atZone(calendar.zone());
            ZonedDateTime start = cursor.isBefore(open) ? open : cursor;
            if (!start.isBefore(close)) {
                cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
                continue;
            }
            long availableMinutes = Duration.between(start, close).toMinutes();
            if (availableMinutes >= remaining) {
                return start.plusMinutes(remaining).toInstant();
            }
            remaining -= availableMinutes;
            cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
        }
        // Unreachable with any sane calendar; returning a bounded answer beats
        // returning null and making every caller handle an impossible case.
        return from.plus(Duration.ofDays(MAX_DAYS));
    }

    /**
     * Working minutes elapsed between two instants — used to report how far past
     * its deadline a breached lead actually is, in the units the SLA was set in.
     */
    public static long workingMinutesBetween(Instant from, Instant to, Calendar calendar) {
        if (!to.isAfter(from)) {
            return 0L;
        }
        if (calendar.alwaysOpen()) {
            return Duration.between(from, to).toMinutes();
        }
        ZonedDateTime cursor = from.atZone(calendar.zone());
        ZonedDateTime end = to.atZone(calendar.zone());
        long minutes = 0L;
        for (int day = 0; day <= MAX_DAYS && cursor.isBefore(end); day++) {
            LocalDate date = cursor.toLocalDate();
            Interval interval = calendar.openDays().get(date.getDayOfWeek());
            if (interval != null && !calendar.holidays().contains(date)) {
                ZonedDateTime open = date.atTime(interval.open()).atZone(calendar.zone());
                ZonedDateTime close = date.atTime(interval.close()).atZone(calendar.zone());
                ZonedDateTime segmentStart = cursor.isBefore(open) ? open : cursor;
                ZonedDateTime segmentEnd = end.isBefore(close) ? end : close;
                if (segmentEnd.isAfter(segmentStart)) {
                    minutes += Duration.between(segmentStart, segmentEnd).toMinutes();
                }
            }
            cursor = cursor.plusDays(1).with(LocalTime.MIDNIGHT);
        }
        return minutes;
    }

    /** Convenience for the common Monday-to-Friday shape. */
    public static Calendar weekdays(ZoneId zone, LocalTime open, LocalTime close, Set<LocalDate> holidays) {
        Map<DayOfWeek, Interval> days = new java.util.EnumMap<>(DayOfWeek.class);
        for (DayOfWeek dow : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            days.put(dow, new Interval(open, close));
        }
        return new Calendar(zone, days, holidays);
    }
}
