package com.axiom.orgdata;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public API for E03 organization and reference master data.
 *
 * <p>Every capability behind the new screens is reachable here — nothing is
 * UI-only (Definition of Done point 7, FR-INT-001).
 */
@RestController
@RequestMapping("/api/v1/orgdata")
@Validated
public class OrgDataController {

    private final BusinessUnitService businessUnits;
    private final CurrencyService currencies;
    private final FiscalCalendarService fiscalCalendars;
    private final BusinessHoursService businessHours;
    private final TerritoryService territories;
    private final QuotaService quotas;
    private final MasterChangeControlService changeControl;
    private final MasterGovernanceGate gate;

    public OrgDataController(BusinessUnitService businessUnits, CurrencyService currencies,
                            FiscalCalendarService fiscalCalendars, BusinessHoursService businessHours,
                            TerritoryService territories, QuotaService quotas,
                            MasterChangeControlService changeControl, MasterGovernanceGate gate) {
        this.businessUnits = businessUnits;
        this.currencies = currencies;
        this.fiscalCalendars = fiscalCalendars;
        this.businessHours = businessHours;
        this.territories = territories;
        this.quotas = quotas;
        this.changeControl = changeControl;
        this.gate = gate;
    }

    /* ---------------- Business units (FR-MDM-001) ---------------- */

    @GetMapping("/business-units")
    public List<BusinessUnitService.BusinessUnitRow> businessUnits() {
        return businessUnits.list();
    }

    @PostMapping("/business-units")
    @ResponseStatus(HttpStatus.CREATED)
    public Submission<BusinessUnitService.BusinessUnitRow> createBusinessUnit(
            @RequestBody @Valid BusinessUnitService.BusinessUnitRequest request) {
        return businessUnits.create(request);
    }

    @PostMapping("/business-units/{id}/users")
    public BusinessUnitService.BusinessUnitRow assignUser(
            @PathVariable UUID id, @RequestBody @Valid BusinessUnitService.MemberRequest request) {
        return businessUnits.assignUser(id, request);
    }

    @PostMapping("/business-units/{id}/records")
    public BusinessUnitService.BusinessUnitRow assignRecord(
            @PathVariable UUID id, @RequestBody @Valid BusinessUnitService.RecordScopeRequest request) {
        return businessUnits.assignRecord(id, request);
    }

    @GetMapping("/business-units/{id}/scope")
    public BusinessUnitService.ScopeSummary businessUnitScope(@PathVariable UUID id) {
        return businessUnits.scope(id);
    }

    /* ---------------- Currencies and rates (FR-MDM-002/003) ---------------- */

    @GetMapping("/currencies")
    public List<CurrencyService.CurrencyRow> currencies(
            @RequestParam(defaultValue = "true") boolean includeInactive) {
        return currencies.list(includeInactive);
    }

    @GetMapping("/currencies/corporate")
    public CurrencyService.CurrencyRow corporateCurrency() {
        return currencies.corporateCurrency();
    }

    @PostMapping("/currencies")
    @ResponseStatus(HttpStatus.CREATED)
    public Submission<CurrencyService.CurrencyRow> createCurrency(
            @RequestBody @Valid CurrencyService.CurrencyRequest request) {
        return currencies.createCurrency(request);
    }

    @PatchMapping("/currencies/{code}/active")
    public CurrencyService.CurrencyRow setCurrencyActive(@PathVariable String code,
                                                         @RequestBody Map<String, Boolean> body) {
        return currencies.setActive(code, Boolean.TRUE.equals(body.get("active")));
    }

    @PutMapping("/currencies/corporate/{code}")
    public CurrencyService.CurrencyRow setCorporateCurrency(@PathVariable String code) {
        return currencies.setCorporateCurrency(code);
    }

    @GetMapping("/exchange-rates")
    public List<CurrencyService.RateRow> rates(@RequestParam(defaultValue = "") String from,
                                               @RequestParam(defaultValue = "") String to) {
        return currencies.rates(from, to);
    }

    @PostMapping("/exchange-rates")
    @ResponseStatus(HttpStatus.CREATED)
    public Submission<CurrencyService.RateRow> createRate(
            @RequestBody @Valid CurrencyService.RateRequest request) {
        return currencies.createRate(request);
    }

    @GetMapping("/exchange-rates/on")
    public CurrencyService.RateRow rateOn(@RequestParam String from, @RequestParam String to,
                                          @RequestParam LocalDate date) {
        return currencies.rateOn(from, to, date);
    }

    @GetMapping("/conversion-policies")
    public List<CurrencyService.PolicyRow> conversionPolicies() {
        return currencies.policies();
    }

    @PutMapping("/conversion-policies")
    public CurrencyService.PolicyRow upsertConversionPolicy(
            @RequestBody @Valid CurrencyService.PolicyRequest request) {
        return currencies.upsertPolicy(request);
    }

    @PostMapping("/conversions")
    @ResponseStatus(HttpStatus.CREATED)
    public CurrencyService.ConversionRow convert(
            @RequestBody @Valid CurrencyService.ConvertRequest request) {
        return currencies.convert(request);
    }

    @GetMapping("/conversions/{entityType}/{entityId}")
    public List<CurrencyService.ConversionRow> conversions(@PathVariable String entityType,
                                                           @PathVariable UUID entityId) {
        return currencies.conversions(entityType, entityId);
    }

    /* ---------------- Fiscal calendar (FR-MDM-004) ---------------- */

    @GetMapping("/fiscal-calendars")
    public List<FiscalCalendarService.CalendarRow> fiscalCalendars() {
        return fiscalCalendars.list();
    }

    @PostMapping("/fiscal-calendars")
    @ResponseStatus(HttpStatus.CREATED)
    public FiscalCalendarService.CalendarRow createFiscalCalendar(
            @RequestBody @Valid FiscalCalendarService.CalendarRequest request) {
        return fiscalCalendars.createCalendar(request);
    }

    @GetMapping("/fiscal-calendars/{id}/years")
    public List<FiscalCalendarService.YearRow> fiscalYears(@PathVariable UUID id) {
        return fiscalCalendars.years(id);
    }

    @GetMapping("/fiscal-calendars/{id}/periods")
    public List<FiscalCalendarService.PeriodRow> fiscalPeriods(
            @PathVariable UUID id, @RequestParam(defaultValue = "") String periodType) {
        return fiscalCalendars.periods(id, periodType);
    }

    @PostMapping("/fiscal-calendars/{id}/years")
    @ResponseStatus(HttpStatus.CREATED)
    public List<FiscalCalendarService.PeriodRow> generateFiscalYear(
            @PathVariable UUID id, @RequestBody @Valid FiscalCalendarService.YearRequest request) {
        return fiscalCalendars.generateYear(id, request);
    }

    @GetMapping("/fiscal-periods/resolve")
    public FiscalCalendarService.PeriodRow resolveFiscalPeriod(
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "QUARTER") String periodType) {
        return fiscalCalendars.resolve(date, periodType);
    }

    /* ---------------- Business hours (FR-MDM-005) ---------------- */

    @GetMapping("/business-hours")
    public List<BusinessHoursService.BusinessHoursRow> businessHours() {
        return businessHours.list();
    }

    @PostMapping("/business-hours")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessHoursService.BusinessHoursRow createBusinessHours(
            @RequestBody @Valid BusinessHoursService.BusinessHoursRequest request) {
        return businessHours.create(request);
    }

    @PostMapping("/business-hours/{id}/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessHoursService.BusinessHoursRow addHoliday(
            @PathVariable UUID id, @RequestBody @Valid BusinessHoursService.HolidayRequest request) {
        return businessHours.addHoliday(id, request);
    }

    @GetMapping("/business-hours/sla")
    public BusinessHoursService.SlaResult sla(
            @RequestParam(required = false) UUID businessHoursId,
            @RequestParam OffsetDateTime startAt,
            @RequestParam int targetMinutes) {
        return businessHours.dueAt(businessHoursId, startAt, targetMinutes);
    }

    /* ---------------- Territories (FR-MDM-008) ---------------- */

    @GetMapping("/territory-models")
    public List<TerritoryService.ModelRow> territoryModels() {
        return territories.models();
    }

    @PostMapping("/territory-models")
    @ResponseStatus(HttpStatus.CREATED)
    public TerritoryService.ModelRow createTerritoryModel(
            @RequestBody @Valid TerritoryService.ModelRequest request) {
        return territories.createModel(request);
    }

    @GetMapping("/territory-models/{id}/territories")
    public List<TerritoryService.TerritoryRow> territories(@PathVariable UUID id) {
        return territories.territories(id);
    }

    @PostMapping("/territory-models/{id}/territories")
    @ResponseStatus(HttpStatus.CREATED)
    public TerritoryService.TerritoryRow addTerritory(
            @PathVariable UUID id, @RequestBody @Valid TerritoryService.TerritoryRequest request) {
        return territories.addTerritory(id, request);
    }

    @GetMapping("/territory-models/{id}/rules")
    public List<TerritoryService.RuleRow> territoryRules(@PathVariable UUID id) {
        return territories.rules(id);
    }

    @PostMapping("/territory-models/{id}/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public TerritoryService.RuleRow addTerritoryRule(
            @PathVariable UUID id, @RequestBody @Valid TerritoryService.RuleRequest request) {
        return territories.addRule(id, request);
    }

    @PostMapping("/territories/{id}/members")
    public TerritoryService.TerritoryRow addTerritoryMember(
            @PathVariable UUID id, @RequestBody @Valid TerritoryService.MemberRequest request) {
        return territories.addMember(id, request);
    }

    /** Dry run. Read-only: nothing is assigned (US-E03-06). */
    @GetMapping("/territory-models/{id}/preview")
    public TerritoryService.PreviewResult previewTerritoryModel(@PathVariable UUID id) {
        return territories.preview(id);
    }

    @PostMapping("/territory-models/{id}/activate")
    public TerritoryService.ActivationResult activateTerritoryModel(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return territories.activate(id, body == null ? null : body.get("reason"));
    }

    @PostMapping("/territory-models/{id}/restore")
    public TerritoryService.ActivationResult restoreTerritoryModel(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return territories.restore(id, body == null ? null : body.get("reason"));
    }

    @GetMapping("/territory-models/active/assignment")
    public List<TerritoryService.PreviewLine> activeTerritoryAssignment() {
        return territories.activeAssignment();
    }

    /* ---------------- Quotas (FR-MDM-009) ---------------- */

    @GetMapping("/quotas")
    public List<QuotaService.QuotaRow> quotas(
            @RequestParam(defaultValue = "") String subjectType,
            @RequestParam(required = false) UUID fiscalPeriodId,
            @RequestParam(defaultValue = "false") boolean includeSuperseded) {
        return quotas.list(subjectType, fiscalPeriodId, includeSuperseded);
    }

    @PostMapping("/quotas")
    @ResponseStatus(HttpStatus.CREATED)
    public Submission<QuotaService.QuotaRow> saveQuota(
            @RequestBody @Valid QuotaService.QuotaRequest request) {
        return quotas.save(request);
    }

    @GetMapping("/quotas/{id}/versions")
    public List<QuotaService.QuotaRow> quotaVersions(@PathVariable UUID id) {
        return quotas.history(id);
    }

    /* ---------------- Change control (FR-MDM-010) ---------------- */

    @GetMapping("/governed-masters")
    public List<MasterGovernanceGate.GovernedMaster> governedMasters() {
        return gate.registry();
    }

    @PatchMapping("/governed-masters/{masterType}")
    public MasterGovernanceGate.GovernedMaster setGovernance(@PathVariable String masterType,
                                                              @RequestBody Map<String, Boolean> body) {
        return gate.setRequiresApproval(masterType, Boolean.TRUE.equals(body.get("requiresApproval")));
    }

    @GetMapping("/change-requests")
    public List<MasterChangeControlService.ChangeRequestRow> changeRequests(
            @RequestParam(defaultValue = "") String status) {
        return changeControl.list(status);
    }

    @PostMapping("/change-requests/{id}/approve")
    public MasterChangeControlService.ChangeRequestRow approveChange(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid MasterChangeControlService.DecisionRequest request) {
        return changeControl.approve(id, request);
    }

    @PostMapping("/change-requests/{id}/reject")
    public MasterChangeControlService.ChangeRequestRow rejectChange(
            @PathVariable UUID id,
            @RequestBody @Valid MasterChangeControlService.DecisionRequest request) {
        return changeControl.reject(id, request);
    }
}
