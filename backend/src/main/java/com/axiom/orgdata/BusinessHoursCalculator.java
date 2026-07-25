package com.axiom.orgdata;

import com.axiom.common.ConflictException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;

/**
 * FR-MDM-005 — the business-hours clock, kept as a pure function.
 *
 * <p>This is what an SLA due time, a cadence step and a scheduled job all have
 * to agree on. Keeping it free of the database and the wall clock is what makes
 * "a lead assigned at 17:55 on Friday is due at 09:25 on Monday" an assertion in
 * a test rather than a claim in a document.
 *
 * <p>Two behaviours are deliberate:
 * <ul>
 *   <li>A start time <em>outside</em> business hours does not consume any of the
 *       target — the clock starts at the next opening. An SLA that silently ran
 *       overnight would be unfair to the owner and wrong on the report.</li>
 *   <li>Holidays are skipped whole. A holiday is a closed day, not a shortened
 *       one.</li>
 * </ul>
 */
public final class BusinessHoursCalculator {

    /** One day's opening window. */
    public record OpenWindow(LocalTime open, LocalTime close) {}

    /** Guard against an unsatisfiable definition (e.g. every day a holiday). */
    private static final int MAX_DAYS_SCANNED = 3650;

    private BusinessHoursCalculator() {}

    /**
     * @param start    when the clock starts (any zone; converted to {@code zone})
     * @param zone     the business-hours time zone (M9: local time is business-meaningful)
     * @param weekly   ISO day-of-week (1 = Monday .. 7 = Sunday) to opening window
     * @param holidays closed dates, in {@code zone}
     * @param target   the amount of business time to consume
     * @return the instant at which {@code target} business time has elapsed
     */
    public static ZonedDateTime dueAt(ZonedDateTime start, ZoneId zone,
                                      Map<Integer, OpenWindow> weekly,
                                      Set<LocalDate> holidays, Duration target) {
        if (weekly == null || weekly.isEmpty()) {
            throw new ConflictException("This business-hours definition has no open days. "
                    + "Add at least one working day before using it for an SLA.");
        }
        if (target == null || target.isNegative()) {
            throw new ConflictException("An SLA target must be a positive amount of time.");
        }
        ZonedDateTime cursor = start.withZoneSameInstant(zone);
        long remaining = target.toSeconds();
        int daysScanned = 0;

        while (true) {
            LocalDate date = cursor.toLocalDate();
            OpenWindow window = weekly.get(date.getDayOfWeek().getValue());
            boolean closed = window == null || (holidays != null && holidays.contains(date));
            if (closed) {
                cursor = nextDayOpening(date, zone, weekly, holidays, ++daysScanned);
                continue;
            }
            ZonedDateTime open = ZonedDateTime.of(LocalDateTime.of(date, window.open()), zone);
            ZonedDateTime close = ZonedDateTime.of(LocalDateTime.of(date, window.close()), zone);
            if (cursor.isBefore(open)) {
                cursor = open;
            }
            if (!cursor.isBefore(close)) {
                cursor = nextDayOpening(date, zone, weekly, holidays, ++daysScanned);
                continue;
            }
            long availableToday = Duration.between(cursor, close).toSeconds();
            if (remaining <= availableToday) {
                return cursor.plusSeconds(remaining);
            }
            remaining -= availableToday;
            cursor = nextDayOpening(date, zone, weekly, holidays, ++daysScanned);
        }
    }

    /** Business seconds available between two instants, used for elapsed reporting. */
    public static long businessSecondsBetween(ZonedDateTime from, ZonedDateTime to, ZoneId zone,
                                              Map<Integer, OpenWindow> weekly, Set<LocalDate> holidays) {
        if (!to.isAfter(from)) return 0L;
        ZonedDateTime cursor = from.withZoneSameInstant(zone);
        ZonedDateTime end = to.withZoneSameInstant(zone);
        long total = 0L;
        int daysScanned = 0;
        while (cursor.isBefore(end) && daysScanned++ < MAX_DAYS_SCANNED) {
            LocalDate date = cursor.toLocalDate();
            OpenWindow window = weekly.get(date.getDayOfWeek().getValue());
            if (window != null && (holidays == null || !holidays.contains(date))) {
                ZonedDateTime open = ZonedDateTime.of(LocalDateTime.of(date, window.open()), zone);
                ZonedDateTime close = ZonedDateTime.of(LocalDateTime.of(date, window.close()), zone);
                ZonedDateTime sliceStart = cursor.isAfter(open) ? cursor : open;
                ZonedDateTime sliceEnd = end.isBefore(close) ? end : close;
                if (sliceEnd.isAfter(sliceStart)) {
                    total += Duration.between(sliceStart, sliceEnd).toSeconds();
                }
            }
            cursor = ZonedDateTime.of(LocalDateTime.of(date.plusDays(1), LocalTime.MIDNIGHT), zone);
        }
        return total;
    }

    private static ZonedDateTime nextDayOpening(LocalDate from, ZoneId zone,
                                                Map<Integer, OpenWindow> weekly,
                                                Set<LocalDate> holidays, int daysScanned) {
        if (daysScanned > MAX_DAYS_SCANNED) {
            throw new ConflictException("This business-hours definition never opens — "
                    + "every day in the next ten years is closed or a holiday. Correct the definition.");
        }
        LocalDate next = from.plusDays(1);
        OpenWindow window = weekly.get(next.getDayOfWeek().getValue());
        if (window == null || (holidays != null && holidays.contains(next))) {
            return nextDayOpening(next, zone, weekly, holidays, daysScanned + 1);
        }
        return ZonedDateTime.of(LocalDateTime.of(next, window.open()), zone);
    }
}
