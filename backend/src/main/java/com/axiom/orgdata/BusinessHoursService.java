package com.axiom.orgdata;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.axiom.tenancy.TenantContext.Principal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-MDM-005 — named business-hours definitions with holidays and time zones,
 * and the SLA clock that consumes them.
 *
 * <p>{@link #dueAt} is the endpoint SLA clocks, cadences and scheduled work call.
 * It returns the computed instant <em>and</em> the definition it used, because a
 * due time without its provenance is impossible to argue with when a customer
 * disputes a breach.
 */
@Service
public class BusinessHoursService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public BusinessHoursService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record DayRow(int dayOfWeek, LocalTime openTime, LocalTime closeTime) {}

    public record HolidayRow(UUID id, LocalDate holidayDate, String name, boolean recurringAnnually) {}

    public record BusinessHoursRow(UUID id, String code, String name, String timeZone,
                                   boolean isDefault, boolean active, List<DayRow> days,
                                   List<HolidayRow> holidays) {}

    public record DayInput(@Min(1) @Max(7) int dayOfWeek,
                           @NotNull LocalTime openTime, @NotNull LocalTime closeTime) {}

    public record BusinessHoursRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$") @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String timeZone,
            boolean makeDefault,
            List<DayInput> days) {}

    public record HolidayRequest(@NotNull LocalDate holidayDate,
                                 @NotBlank @Size(max = 120) String name,
                                 boolean recurringAnnually) {}

    public record SlaResult(UUID businessHoursId, String businessHoursCode, String timeZone,
                            OffsetDateTime startAt, int targetMinutes, OffsetDateTime dueAt,
                            List<LocalDate> holidaysSkipped) {}

    @Transactional(readOnly = true)
    public List<BusinessHoursRow> list() {
        UUID tenantId = TenantContext.get().tenantId();
        List<BusinessHoursRow> shells = jdbc.query("""
                select id, code, name, time_zone, is_default, active
                from orgdata.business_hours where tenant_id = ?
                order by is_default desc, code
                """, (rs, i) -> new BusinessHoursRow(rs.getObject("id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("time_zone"),
                rs.getBoolean("is_default"), rs.getBoolean("active"), List.of(), List.of()),
                tenantId);
        return shells.stream().map(shell -> new BusinessHoursRow(shell.id(), shell.code(),
                shell.name(), shell.timeZone(), shell.isDefault(), shell.active(),
                days(shell.id()), holidays(shell.id()))).toList();
    }

    @Transactional(readOnly = true)
    public List<DayRow> days(UUID businessHoursId) {
        return jdbc.query("""
                select day_of_week, open_time, close_time
                from orgdata.business_hours_day
                where tenant_id = ? and business_hours_id = ?
                order by day_of_week
                """, (rs, i) -> new DayRow(rs.getInt("day_of_week"),
                rs.getObject("open_time", LocalTime.class), rs.getObject("close_time", LocalTime.class)),
                TenantContext.get().tenantId(), businessHoursId);
    }

    /**
     * Holidays for a definition: those attached to it, plus tenant-wide ones
     * (null {@code business_hours_id}) which apply to every definition.
     */
    @Transactional(readOnly = true)
    public List<HolidayRow> holidays(UUID businessHoursId) {
        return jdbc.query("""
                select id, holiday_date, name, recurring_annually
                from orgdata.holiday
                where tenant_id = ? and (business_hours_id = ? or business_hours_id is null)
                order by holiday_date
                """, (rs, i) -> new HolidayRow(rs.getObject("id", UUID.class),
                rs.getObject("holiday_date", LocalDate.class), rs.getString("name"),
                rs.getBoolean("recurring_annually")),
                TenantContext.get().tenantId(), businessHoursId);
    }

    @Transactional
    public BusinessHoursRow create(BusinessHoursRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        ZoneId zone = parseZone(request.timeZone());
        if (request.days() == null || request.days().isEmpty()) {
            throw new ConflictException("Add at least one working day. A business-hours definition "
                    + "with no open days would make every SLA unsatisfiable.");
        }
        request.days().forEach(day -> {
            if (!day.closeTime().isAfter(day.openTime())) {
                throw new ConflictException("Day " + day.dayOfWeek()
                        + " closes at or before it opens. Correct the times and retry.");
            }
        });
        try {
            if (request.makeDefault()) {
                jdbc.update("update orgdata.business_hours set is_default = false where tenant_id = ? and is_default",
                        p.tenantId());
            }
            UUID id = jdbc.queryForObject("""
                    insert into orgdata.business_hours
                      (tenant_id, code, name, time_zone, is_default, created_by)
                    values (?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, p.tenantId(), code, request.name().trim(), zone.getId(),
                    request.makeDefault(), p.userId());
            request.days().forEach(day -> jdbc.update("""
                    insert into orgdata.business_hours_day
                      (tenant_id, business_hours_id, day_of_week, open_time, close_time)
                    values (?, ?, ?, ?, ?)
                    on conflict (tenant_id, business_hours_id, day_of_week) do update
                      set open_time = excluded.open_time, close_time = excluded.close_time
                    """, p.tenantId(), id, day.dayOfWeek(), day.openTime(), day.closeTime()));
            audit.record("BUSINESS_HOURS_CREATE", "BUSINESS_HOURS", id,
                    "Created business hours " + code + " (" + zone.getId() + ")",
                    Map.of("code", code, "timeZone", zone.getId(),
                            "openDays", request.days().size(), "isDefault", request.makeDefault()));
            return byId(id);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Business hours " + code
                    + " already exist. Choose a different code or edit the existing definition.");
        }
    }

    @Transactional
    public BusinessHoursRow addHoliday(UUID businessHoursId, HolidayRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        BusinessHoursRow hours = byId(businessHoursId);
        try {
            jdbc.update("""
                    insert into orgdata.holiday
                      (tenant_id, business_hours_id, holiday_date, name, recurring_annually, created_by)
                    values (?, ?, ?, ?, ?, ?)
                    """, p.tenantId(), businessHoursId, request.holidayDate(),
                    request.name().trim(), request.recurringAnnually(), p.userId());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A holiday is already recorded on " + request.holidayDate()
                    + " for " + hours.code() + ".");
        }
        audit.record("HOLIDAY_CREATE", "BUSINESS_HOURS", businessHoursId,
                "Added holiday " + request.name().trim() + " on " + request.holidayDate()
                        + " to " + hours.code(),
                Map.of("holidayDate", request.holidayDate().toString(), "name", request.name().trim(),
                        "recurringAnnually", request.recurringAnnually()));
        return byId(businessHoursId);
    }

    /**
     * The SLA clock (FR-MDM-005). Business hours only; holidays excluded.
     *
     * @param businessHoursId null uses the tenant's default definition
     */
    @Transactional(readOnly = true)
    public SlaResult dueAt(UUID businessHoursId, OffsetDateTime startAt, int targetMinutes) {
        if (targetMinutes <= 0) {
            throw new ConflictException("The SLA target must be a positive number of minutes.");
        }
        BusinessHoursRow hours = businessHoursId == null ? defaultHours() : byId(businessHoursId);
        ZoneId zone = parseZone(hours.timeZone());
        Map<Integer, BusinessHoursCalculator.OpenWindow> weekly = new LinkedHashMap<>();
        hours.days().forEach(day -> weekly.put(day.dayOfWeek(),
                new BusinessHoursCalculator.OpenWindow(day.openTime(), day.closeTime())));

        OffsetDateTime start = startAt == null ? OffsetDateTime.now() : startAt;
        ZonedDateTime localStart = start.atZoneSameInstant(zone);
        // Expand recurring holidays across the window the clock could plausibly
        // reach, so an annual holiday is honoured in every year, not just the
        // year it was first recorded.
        Set<LocalDate> holidayDates = expandHolidays(hours.holidays(),
                localStart.toLocalDate().minusDays(1), localStart.toLocalDate().plusYears(2));

        ZonedDateTime due = BusinessHoursCalculator.dueAt(localStart, zone, weekly, holidayDates,
                Duration.ofMinutes(targetMinutes));
        List<LocalDate> skipped = holidayDates.stream()
                .filter(date -> !date.isBefore(localStart.toLocalDate()))
                .filter(date -> !date.isAfter(due.toLocalDate()))
                .sorted().toList();
        return new SlaResult(hours.id(), hours.code(), hours.timeZone(), start, targetMinutes,
                due.toOffsetDateTime(), skipped);
    }

    static Set<LocalDate> expandHolidays(List<HolidayRow> holidays, LocalDate from, LocalDate to) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        holidays.forEach(holiday -> {
            if (!holiday.recurringAnnually()) {
                dates.add(holiday.holidayDate());
                return;
            }
            for (int year = from.getYear(); year <= to.getYear(); year++) {
                try {
                    dates.add(holiday.holidayDate().withYear(year));
                } catch (DateTimeException ignored) {
                    // 29 February in a non-leap year: no such business day exists.
                }
            }
        });
        return dates;
    }

    @Transactional(readOnly = true)
    public BusinessHoursRow byId(UUID id) {
        return list().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Business hours definition not found"));
    }

    @Transactional(readOnly = true)
    public BusinessHoursRow defaultHours() {
        try {
            UUID id = jdbc.queryForObject(
                    "select id from orgdata.business_hours where tenant_id = ? and is_default",
                    UUID.class, TenantContext.get().tenantId());
            return byId(id);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("No default business-hours definition is configured for this tenant");
        }
    }

    private static ZoneId parseZone(String timeZone) {
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException ex) {
            throw new ConflictException("'" + timeZone + "' is not a recognized time zone. "
                    + "Use an IANA identifier such as Asia/Kolkata or Europe/London.");
        }
    }
}
