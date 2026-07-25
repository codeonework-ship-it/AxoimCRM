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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-004 — fiscal calendars, years, quarters and periods.
 *
 * <p>The arithmetic lives in {@link FiscalPeriodPlanner}; this service persists
 * the result and answers "which period is this date in", which is the question
 * forecasting, quota and reporting all ask. They ask the same service so they
 * cannot disagree.
 *
 * <p>US-E03-02 requires that a calendar change affecting historical periods is
 * refused or explicitly confirmed. Adding a year is always safe; regenerating a
 * year that already carries quotas is refused unless the caller confirms, and
 * the refusal names what would be affected.
 */
@Service
public class FiscalCalendarService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FiscalCalendarService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CalendarRow(UUID id, String code, String name, String calendarType,
                              int startMonth, int startDay, boolean isDefault, boolean active,
                              int yearCount) {}

    public record YearRow(UUID id, UUID calendarId, String yearLabel,
                          LocalDate startDate, LocalDate endDate, int periodCount) {}

    public record PeriodRow(UUID id, UUID fiscalYearId, String yearLabel, String periodType,
                            Integer quarterNumber, Integer periodNumber, String label,
                            LocalDate startDate, LocalDate endDate) {}

    public record CalendarRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$") @Size(max = 40) String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "STANDARD|CUSTOM|FOUR_FOUR_FIVE") String calendarType,
            @Min(1) @Max(12) int startMonth,
            @Min(1) @Max(28) int startDay,
            boolean makeDefault) {}

    public record CustomPeriod(@NotBlank String label, @NotNull LocalDate startDate,
                               @NotNull LocalDate endDate, Integer quarter, Integer period) {}

    public record YearRequest(
            @NotBlank @Size(max = 20) String yearLabel,
            @NotNull LocalDate startDate,
            /** CUSTOM only. */
            LocalDate endDate,
            List<CustomPeriod> periods,
            /** Required when regenerating a year that already has quotas attached. */
            boolean confirmHistoricalImpact,
            String reason) {}

    @Transactional(readOnly = true)
    public List<CalendarRow> list() {
        return jdbc.query("""
                select c.id, c.code, c.name, c.calendar_type, c.start_month, c.start_day,
                       c.is_default, c.active,
                       (select count(*) from orgdata.fiscal_year y
                         where y.tenant_id = c.tenant_id and y.calendar_id = c.id) as year_count
                from orgdata.fiscal_calendar c
                where c.tenant_id = ?
                order by c.is_default desc, c.code
                """, (rs, i) -> new CalendarRow(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("calendar_type"), rs.getInt("start_month"),
                rs.getInt("start_day"), rs.getBoolean("is_default"), rs.getBoolean("active"),
                rs.getInt("year_count")), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<YearRow> years(UUID calendarId) {
        return jdbc.query("""
                select y.id, y.calendar_id, y.year_label, y.start_date, y.end_date,
                       (select count(*) from orgdata.fiscal_period p
                         where p.tenant_id = y.tenant_id and p.fiscal_year_id = y.id) as period_count
                from orgdata.fiscal_year y
                where y.tenant_id = ? and y.calendar_id = ?
                order by y.start_date
                """, (rs, i) -> new YearRow(rs.getObject("id", UUID.class),
                rs.getObject("calendar_id", UUID.class), rs.getString("year_label"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                rs.getInt("period_count")), TenantContext.get().tenantId(), calendarId);
    }

    @Transactional(readOnly = true)
    public List<PeriodRow> periods(UUID calendarId, String periodType) {
        String type = periodType == null ? "" : periodType.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("""
                select p.id, p.fiscal_year_id, y.year_label, p.period_type, p.quarter_number,
                       p.period_number, p.label, p.start_date, p.end_date
                from orgdata.fiscal_period p
                join orgdata.fiscal_year y on y.tenant_id = p.tenant_id and y.id = p.fiscal_year_id
                where p.tenant_id = ? and y.calendar_id = ? and (? = '' or p.period_type = ?)
                order by y.start_date, p.period_type desc, p.start_date
                """, FiscalCalendarService::mapPeriod, TenantContext.get().tenantId(),
                calendarId, type, type);
    }

    /**
     * The single period-resolution entry point shared by forecasting, quota and
     * reporting (FR-MDM-004).
     */
    @Transactional(readOnly = true)
    public PeriodRow resolve(LocalDate date, String periodType) {
        String type = periodType == null || periodType.isBlank()
                ? "QUARTER" : periodType.trim().toUpperCase(Locale.ROOT);
        List<PeriodRow> rows = jdbc.query("""
                select p.id, p.fiscal_year_id, y.year_label, p.period_type, p.quarter_number,
                       p.period_number, p.label, p.start_date, p.end_date
                from orgdata.fiscal_period p
                join orgdata.fiscal_year y on y.tenant_id = p.tenant_id and y.id = p.fiscal_year_id
                join orgdata.fiscal_calendar c on c.tenant_id = y.tenant_id and c.id = y.calendar_id
                where p.tenant_id = ? and c.is_default and c.active
                  and p.period_type = ? and p.start_date <= ? and p.end_date >= ?
                limit 1
                """, FiscalCalendarService::mapPeriod, TenantContext.get().tenantId(), type, date, date);
        if (rows.isEmpty()) {
            throw new NotFoundException("No fiscal " + type.toLowerCase(Locale.ROOT)
                    + " covers " + date + " in the default calendar. Generate the fiscal year first.");
        }
        return rows.get(0);
    }

    @Transactional
    public CalendarRow createCalendar(CalendarRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        try {
            if (request.makeDefault()) {
                jdbc.update("update orgdata.fiscal_calendar set is_default = false where tenant_id = ? and is_default",
                        p.tenantId());
            }
            UUID id = jdbc.queryForObject("""
                    insert into orgdata.fiscal_calendar
                      (tenant_id, code, name, calendar_type, start_month, start_day, is_default, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, p.tenantId(), code, request.name().trim(),
                    request.calendarType(), request.startMonth(), request.startDay(),
                    request.makeDefault(), p.userId());
            audit.record("FISCAL_CALENDAR_CREATE", "FISCAL_CALENDAR", id,
                    "Created " + request.calendarType() + " fiscal calendar " + code,
                    Map.of("code", code, "calendarType", request.calendarType(),
                            "startMonth", request.startMonth(), "startDay", request.startDay(),
                            "isDefault", request.makeDefault()));
            return byId(id);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A fiscal calendar with code " + code
                    + " already exists. Choose a different code.");
        }
    }

    /**
     * Generates a fiscal year and its quarters and periods.
     *
     * <p>US-E03-02: regenerating a year whose periods already carry quotas is
     * refused unless the caller confirms, and the refusal names the count so the
     * operator knows what they are agreeing to.
     */
    @Transactional
    public List<PeriodRow> generateYear(UUID calendarId, YearRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        CalendarRow calendar = byId(calendarId);
        String label = request.yearLabel().trim().toUpperCase(Locale.ROOT);

        List<FiscalPeriodPlanner.PlannedPeriod> custom = new ArrayList<>();
        if (request.periods() != null) {
            request.periods().forEach(period -> custom.add(new FiscalPeriodPlanner.PlannedPeriod(
                    period.period() == null ? "QUARTER" : "PERIOD", period.quarter(),
                    period.period(), period.label(), period.startDate(), period.endDate())));
        }
        FiscalPeriodPlanner.PlannedYear planned = FiscalPeriodPlanner.plan(
                calendar.calendarType(), label, request.startDate(), request.endDate(), custom);

        UUID existingYearId = jdbc.query("""
                select id from orgdata.fiscal_year
                where tenant_id = ? and calendar_id = ? and year_label = ?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                p.tenantId(), calendarId, label);
        if (existingYearId != null) {
            Integer attachedQuotas = jdbc.queryForObject("""
                    select count(*) from orgdata.quota q
                    join orgdata.fiscal_period fp on fp.tenant_id = q.tenant_id and fp.id = q.fiscal_period_id
                    where q.tenant_id = ? and fp.fiscal_year_id = ?
                    """, Integer.class, p.tenantId(), existingYearId);
            int affected = attachedQuotas == null ? 0 : attachedQuotas;
            if (affected > 0 && !request.confirmHistoricalImpact()) {
                throw new ConflictException(("Fiscal year %s already exists and %d quota record%s "
                        + "reference%s its periods. Regenerating it would move those periods. "
                        + "Resubmit with confirmHistoricalImpact set to true if that is intended.")
                        .formatted(label, affected, affected == 1 ? "" : "s", affected == 1 ? "s" : ""));
            }
            jdbc.update("""
                    delete from orgdata.fiscal_period
                    where tenant_id = ? and fiscal_year_id = ?
                      and id not in (select fiscal_period_id from orgdata.quota where tenant_id = ?)
                    """, p.tenantId(), existingYearId, p.tenantId());
            jdbc.update("""
                    update orgdata.fiscal_year set start_date = ?, end_date = ?
                    where tenant_id = ? and id = ?
                    """, planned.startDate(), planned.endDate(), p.tenantId(), existingYearId);
        } else {
            existingYearId = jdbc.queryForObject("""
                    insert into orgdata.fiscal_year (tenant_id, calendar_id, year_label, start_date, end_date)
                    values (?, ?, ?, ?, ?) returning id
                    """, UUID.class, p.tenantId(), calendarId, label,
                    planned.startDate(), planned.endDate());
        }

        UUID yearId = existingYearId;
        planned.periods().forEach(period -> jdbc.update("""
                insert into orgdata.fiscal_period
                  (tenant_id, fiscal_year_id, period_type, quarter_number, period_number,
                   label, start_date, end_date)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, fiscal_year_id, period_type, label) do update
                  set quarter_number = excluded.quarter_number,
                      period_number = excluded.period_number,
                      start_date = excluded.start_date,
                      end_date = excluded.end_date
                """, p.tenantId(), yearId, period.type(), period.quarter(), period.period(),
                period.label(), period.startDate(), period.endDate()));

        audit.recordWithReason("FISCAL_YEAR_GENERATE", "FISCAL_CALENDAR", calendarId,
                "Generated fiscal year " + label + " on calendar " + calendar.code(),
                request.reason(),
                Map.of("yearLabel", label, "calendarType", calendar.calendarType(),
                        "startDate", planned.startDate().toString(),
                        "endDate", planned.endDate().toString(),
                        "periodCount", planned.periods().size(),
                        "confirmedHistoricalImpact", request.confirmHistoricalImpact()));
        return periods(calendarId, null);
    }

    @Transactional(readOnly = true)
    public CalendarRow byId(UUID id) {
        return list().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Fiscal calendar not found"));
    }

    @Transactional(readOnly = true)
    public CalendarRow defaultCalendar() {
        try {
            UUID id = jdbc.queryForObject(
                    "select id from orgdata.fiscal_calendar where tenant_id = ? and is_default",
                    UUID.class, TenantContext.get().tenantId());
            return byId(id);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("No default fiscal calendar is configured for this tenant");
        }
    }

    private static PeriodRow mapPeriod(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        Integer quarter = rs.getObject("quarter_number", Integer.class);
        Integer period = rs.getObject("period_number", Integer.class);
        return new PeriodRow(rs.getObject("id", UUID.class),
                rs.getObject("fiscal_year_id", UUID.class), rs.getString("year_label"),
                rs.getString("period_type"), quarter, period, rs.getString("label"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class));
    }
}
