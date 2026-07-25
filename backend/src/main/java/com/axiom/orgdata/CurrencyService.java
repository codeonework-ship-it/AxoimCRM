package com.axiom.orgdata;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.axiom.tenancy.TenantContext.Principal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-002 and FR-MDM-003 — multi-currency with dated exchange rates.
 *
 * <p>Three rules drive the whole design here and each of them is a rule someone
 * eventually tries to break:
 *
 * <ol>
 *   <li><b>A stored conversion is never silently recomputed.</b> Conversions are
 *       written to {@code orgdata.money_conversion} carrying the rate and the
 *       rate date that produced them. Reading a historical record returns the
 *       stored row. Re-converting is an explicit, audited act that writes a new
 *       row and supersedes the old one — the old row is retained.</li>
 *   <li><b>A currency pair may not have two rates in force on one day.</b>
 *       Enforced here with an actionable message, and again in the database with
 *       an exclusion constraint, because "which rate applied on the close date"
 *       must have exactly one answer.</li>
 *   <li><b>Which date a conversion uses is configuration, not code.</b> An
 *       opportunity converts at its close date, a payment at today's rate. The
 *       policy lives in {@code orgdata.currency_conversion_policy}.</li>
 * </ol>
 */
@Service
public class CurrencyService {

    static final String CURRENCY_MASTER = "CURRENCY";
    static final String RATE_MASTER = "EXCHANGE_RATE";
    /** Working precision for the rate multiplication before rounding to the currency's scale. */
    private static final int WORKING_SCALE = 10;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MasterGovernanceGate gate;

    public CurrencyService(JdbcTemplate jdbc, AuditService audit, MasterGovernanceGate gate) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.gate = gate;
    }

    public record CurrencyRow(UUID id, String code, String name, String symbol,
                              int decimalPlaces, boolean corporate, boolean active) {}

    public record RateRow(UUID id, String fromCurrency, String toCurrency, BigDecimal rate,
                          LocalDate effectiveFrom, LocalDate effectiveTo, String source) {}

    public record ConversionRow(UUID id, String entityType, UUID entityId, String entityField,
                                String transactionCurrency, BigDecimal transactionAmount,
                                String corporateCurrency, BigDecimal corporateAmount,
                                BigDecimal appliedRate, LocalDate rateDate, String rateBasis,
                                boolean current) {}

    public record PolicyRow(String objectName, String rateBasis, String recordDateField) {}

    public record CurrencyRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency code must be three letters (ISO 4217)")
            String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 8) String symbol,
            Integer decimalPlaces) {}

    public record RateRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "From currency must be a three-letter ISO code")
            String fromCurrency,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$", message = "To currency must be a three-letter ISO code")
            String toCurrency,
            @NotNull @DecimalMin(value = "0.0000000001", message = "Rate must be greater than zero")
            BigDecimal rate,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String source) {}

    public record ConvertRequest(
            @NotBlank String entityType,
            @NotNull UUID entityId,
            String entityField,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String transactionCurrency,
            @NotNull BigDecimal transactionAmount,
            /** The record's own governing date (close date, order date). */
            LocalDate recordDate,
            /** Set only when deliberately re-converting an existing stored amount. */
            boolean reconvert,
            String reason) {}

    public record PolicyRequest(
            @NotBlank String objectName,
            @NotBlank @Pattern(regexp = "TODAY|RECORD_DATE") String rateBasis,
            String recordDateField) {}

    /* ---------------------------------------------------------------- */
    /* Currencies                                                        */
    /* ---------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<CurrencyRow> list(boolean includeInactive) {
        return jdbc.query("""
                select id, code, name, symbol, decimal_places, is_corporate, active
                from orgdata.currency
                where tenant_id = ? and (? = true or active = true)
                order by is_corporate desc, code
                """, CurrencyService::mapCurrency,
                TenantContext.get().tenantId(), includeInactive);
    }

    @Transactional(readOnly = true)
    public CurrencyRow corporateCurrency() {
        try {
            return jdbc.queryForObject("""
                    select id, code, name, symbol, decimal_places, is_corporate, active
                    from orgdata.currency where tenant_id = ? and is_corporate
                    """, CurrencyService::mapCurrency, TenantContext.get().tenantId());
        } catch (EmptyResultDataAccessException ex) {
            throw new ConflictException("This tenant has no corporate currency. "
                    + "Set one before recording amounts in any other currency.");
        }
    }

    @Transactional
    public Submission<CurrencyRow> createCurrency(CurrencyRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        String code = upper(request.code());
        if (gate.gated(CURRENCY_MASTER)) {
            UUID changeId = gate.enqueue(CURRENCY_MASTER, "CREATE", null,
                    "Activate currency " + code,
                    Map.of("code", code, "name", request.name().trim(),
                            "symbol", request.symbol() == null ? "" : request.symbol(),
                            "decimalPlaces", request.decimalPlaces() == null ? 2 : request.decimalPlaces()));
            return Submission.pending(changeId, "Currency");
        }
        return Submission.applied(applyCurrency(request));
    }

    @Transactional
    public CurrencyRow applyCurrency(CurrencyRequest request) {
        Principal p = TenantContext.get();
        String code = upper(request.code());
        int decimals = request.decimalPlaces() == null ? 2 : request.decimalPlaces();
        if (decimals < 0 || decimals > 4) {
            throw new ConflictException("Decimal places must be between 0 and 4.");
        }
        try {
            CurrencyRow row = jdbc.queryForObject("""
                    insert into orgdata.currency
                      (tenant_id, code, name, symbol, decimal_places, is_corporate, active,
                       created_by, updated_by)
                    values (?, ?, ?, nullif(?, ''), ?, false, true, ?, ?)
                    returning id, code, name, symbol, decimal_places, is_corporate, active
                    """, CurrencyService::mapCurrency, p.tenantId(), code, request.name().trim(),
                    request.symbol() == null ? "" : request.symbol().trim(), decimals,
                    p.userId(), p.userId());
            audit.record("CURRENCY_CREATE", CURRENCY_MASTER, row == null ? null : row.id(),
                    "Activated currency " + code,
                    Map.of("code", code, "name", request.name().trim(), "decimalPlaces", decimals));
            return row;
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Currency " + code + " already exists for this tenant. "
                    + "Reactivate the existing row instead of adding a second one.");
        }
    }

    @Transactional
    public CurrencyRow setActive(String code, boolean active) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        String currency = upper(code);
        CurrencyRow existing = requireCurrency(currency);
        if (existing.corporate() && !active) {
            throw new ConflictException("The corporate currency cannot be deactivated. "
                    + "Nominate a different corporate currency first.");
        }
        jdbc.update("""
                update orgdata.currency set active = ?, updated_at = now(), updated_by = ?
                where tenant_id = ? and code = ?
                """, active, p.userId(), p.tenantId(), currency);
        audit.record("CURRENCY_UPDATE", CURRENCY_MASTER, existing.id(),
                (active ? "Activated" : "Deactivated") + " currency " + currency,
                Map.of("code", currency, "active", active));
        return requireCurrency(currency);
    }

    /**
     * Nominates a new corporate currency. Existing stored conversions keep the
     * corporate currency they were converted into — rewriting them would destroy
     * the very evidence FR-MDM-002 exists to preserve.
     */
    @Transactional
    public CurrencyRow setCorporateCurrency(String code) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        String currency = upper(code);
        CurrencyRow target = requireCurrency(currency);
        if (!target.active()) {
            throw new ConflictException("Currency " + currency
                    + " is inactive. Reactivate it before making it the corporate currency.");
        }
        String previous = jdbc.query("select code from orgdata.currency where tenant_id = ? and is_corporate",
                rs -> rs.next() ? rs.getString(1) : null, p.tenantId());
        jdbc.update("update orgdata.currency set is_corporate = false, updated_at = now(), updated_by = ? "
                + "where tenant_id = ? and is_corporate", p.userId(), p.tenantId());
        jdbc.update("update orgdata.currency set is_corporate = true, updated_at = now(), updated_by = ? "
                + "where tenant_id = ? and code = ?", p.userId(), p.tenantId(), currency);
        audit.record("CURRENCY_CORPORATE_SET", CURRENCY_MASTER, target.id(),
                "Corporate currency set to " + currency,
                Map.of("code", currency, "previous", previous == null ? "" : previous,
                        "storedConversionsRewritten", false));
        return requireCurrency(currency);
    }

    /* ---------------------------------------------------------------- */
    /* Dated exchange rates                                             */
    /* ---------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<RateRow> rates(String fromCurrency, String toCurrency) {
        return jdbc.query("""
                select id, from_currency, to_currency, rate, effective_from, effective_to, source
                from orgdata.exchange_rate
                where tenant_id = ?
                  and (? = '' or from_currency = ?)
                  and (? = '' or to_currency = ?)
                order by from_currency, to_currency, effective_from desc
                """, CurrencyService::mapRate, TenantContext.get().tenantId(),
                upper(fromCurrency), upper(fromCurrency), upper(toCurrency), upper(toCurrency));
    }

    @Transactional
    public Submission<RateRow> createRate(RateRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        validateRate(request);
        if (gate.gated(RATE_MASTER)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fromCurrency", upper(request.fromCurrency()));
            payload.put("toCurrency", upper(request.toCurrency()));
            payload.put("rate", request.rate().toPlainString());
            payload.put("effectiveFrom", request.effectiveFrom().toString());
            payload.put("effectiveTo", request.effectiveTo() == null ? null : request.effectiveTo().toString());
            payload.put("source", request.source() == null ? "MANUAL" : request.source());
            UUID changeId = gate.enqueue(RATE_MASTER, "CREATE", null,
                    "Add %s→%s rate effective %s".formatted(upper(request.fromCurrency()),
                            upper(request.toCurrency()), request.effectiveFrom()), payload);
            return Submission.pending(changeId, "Exchange rate");
        }
        return Submission.applied(applyRate(request));
    }

    @Transactional
    public RateRow applyRate(RateRequest request) {
        Principal p = TenantContext.get();
        validateRate(request);
        String from = upper(request.fromCurrency());
        String to = upper(request.toCurrency());
        String source = request.source() == null || request.source().isBlank()
                ? "MANUAL" : request.source().trim().toUpperCase(Locale.ROOT);
        try {
            RateRow row = jdbc.queryForObject("""
                    insert into orgdata.exchange_rate
                      (tenant_id, from_currency, to_currency, rate, effective_from, effective_to,
                       source, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    returning id, from_currency, to_currency, rate, effective_from, effective_to, source
                    """, CurrencyService::mapRate, p.tenantId(), from, to, request.rate(),
                    request.effectiveFrom(), request.effectiveTo(), source, p.userId());
            audit.record("EXCHANGE_RATE_CREATE", RATE_MASTER, row == null ? null : row.id(),
                    "Added %s→%s rate %s effective %s".formatted(from, to,
                            request.rate().toPlainString(), request.effectiveFrom()),
                    Map.of("fromCurrency", from, "toCurrency", to,
                            "rate", request.rate().toPlainString(),
                            "effectiveFrom", request.effectiveFrom().toString(),
                            "effectiveTo", request.effectiveTo() == null ? "" : request.effectiveTo().toString()));
            return row;
        } catch (DataIntegrityViolationException ex) {
            // The exclusion constraint is the database's own overlap guard; if the
            // in-service check above ever misses a race, this is where it lands.
            throw new ConflictException(overlapMessage(from, to, request.effectiveFrom(), request.effectiveTo()));
        }
    }

    private void validateRate(RateRequest request) {
        String from = upper(request.fromCurrency());
        String to = upper(request.toCurrency());
        if (from.equals(to)) {
            throw new ConflictException("A currency cannot have an exchange rate against itself.");
        }
        if (request.rate() == null || request.rate().signum() <= 0) {
            throw new ConflictException("Exchange rate must be greater than zero.");
        }
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new ConflictException("The rate's effective-to date is before its effective-from date. "
                    + "Correct the dates, or leave effective-to empty for an open-ended rate.");
        }
        Integer overlapping = jdbc.queryForObject("""
                select count(*) from orgdata.exchange_rate
                where tenant_id = ? and from_currency = ? and to_currency = ?
                  and daterange(effective_from, effective_to, '[]')
                      && daterange(?, ?, '[]')
                """, Integer.class, TenantContext.get().tenantId(), from, to,
                request.effectiveFrom(), request.effectiveTo());
        if (overlapping != null && overlapping > 0) {
            throw new ConflictException(overlapMessage(from, to, request.effectiveFrom(), request.effectiveTo()));
        }
    }

    private static String overlapMessage(String from, String to, LocalDate start, LocalDate end) {
        return "A %s→%s rate is already in force during %s to %s. Close the existing rate by setting its "
                .formatted(from, to, start, end == null ? "open-ended" : end.toString())
                + "effective-to date before this range begins, then add the new rate.";
    }

    /** The rate in force for a pair on a given date, or null when none is. */
    @Transactional(readOnly = true)
    public RateRow rateOn(String fromCurrency, String toCurrency, LocalDate date) {
        List<RateRow> rows = jdbc.query("""
                select id, from_currency, to_currency, rate, effective_from, effective_to, source
                from orgdata.exchange_rate
                where tenant_id = ? and from_currency = ? and to_currency = ?
                  and effective_from <= ? and (effective_to is null or effective_to >= ?)
                order by effective_from desc
                limit 1
                """, CurrencyService::mapRate, TenantContext.get().tenantId(),
                upper(fromCurrency), upper(toCurrency), date, date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ---------------------------------------------------------------- */
    /* Conversion policy (FR-MDM-003 "configurable per object")           */
    /* ---------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<PolicyRow> policies() {
        return jdbc.query("""
                select object_name, rate_basis, record_date_field
                from orgdata.currency_conversion_policy
                where tenant_id = ? order by object_name
                """, (rs, i) -> new PolicyRow(rs.getString("object_name"),
                rs.getString("rate_basis"), rs.getString("record_date_field")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public PolicyRow upsertPolicy(PolicyRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        String object = upper(request.objectName());
        String basis = upper(request.rateBasis());
        if ("RECORD_DATE".equals(basis) && (request.recordDateField() == null || request.recordDateField().isBlank())) {
            throw new ConflictException("A record-date policy must name the date field it reads, "
                    + "for example close_date on an opportunity.");
        }
        jdbc.update("""
                insert into orgdata.currency_conversion_policy
                  (tenant_id, object_name, rate_basis, record_date_field, updated_by)
                values (?, ?, ?, nullif(?, ''), ?)
                on conflict (tenant_id, object_name) do update
                  set rate_basis = excluded.rate_basis,
                      record_date_field = excluded.record_date_field,
                      updated_at = now(), updated_by = excluded.updated_by
                """, p.tenantId(), object, basis,
                request.recordDateField() == null ? "" : request.recordDateField().trim(), p.userId());
        audit.record("CONVERSION_POLICY_UPDATE", "CURRENCY_CONVERSION_POLICY", null,
                "Conversion policy for " + object + " set to " + basis,
                Map.of("objectName", object, "rateBasis", basis,
                        "recordDateField", request.recordDateField() == null ? "" : request.recordDateField()));
        return policies().stream().filter(row -> row.objectName().equals(object)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Conversion policy vanished mid-transaction"));
    }

    /* ---------------------------------------------------------------- */
    /* Conversion (M6)                                                   */
    /* ---------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<ConversionRow> conversions(String entityType, UUID entityId) {
        return jdbc.query("""
                select id, entity_type, entity_id, entity_field, transaction_currency,
                       transaction_amount, corporate_currency, corporate_amount, applied_rate,
                       rate_date, rate_basis, is_current
                from orgdata.money_conversion
                where tenant_id = ? and entity_type = ? and entity_id = ?
                order by converted_at desc
                """, CurrencyService::mapConversion, TenantContext.get().tenantId(),
                upper(entityType), entityId);
    }

    /**
     * Converts and stores a monetary amount.
     *
     * <p>The rate date comes from the object's conversion policy: a
     * {@code RECORD_DATE} object uses the caller-supplied record date (close
     * date, order date), a {@code TODAY} object uses the current date. A stored
     * conversion is returned as-is unless the caller explicitly asks to
     * re-convert — that is the "never silently recomputed" rule, and it is why
     * {@code reconvert} exists rather than being inferred.
     */
    @Transactional
    public ConversionRow convert(ConvertRequest request) {
        Principal p = TenantContext.get();
        String entityType = upper(request.entityType());
        String field = request.entityField() == null || request.entityField().isBlank()
                ? "amount" : request.entityField().trim();
        CurrencyRow corporate = corporateCurrency();
        String txCurrency = upper(request.transactionCurrency());

        ConversionRow stored = jdbc.query("""
                select id, entity_type, entity_id, entity_field, transaction_currency,
                       transaction_amount, corporate_currency, corporate_amount, applied_rate,
                       rate_date, rate_basis, is_current
                from orgdata.money_conversion
                where tenant_id = ? and entity_type = ? and entity_id = ? and entity_field = ?
                  and is_current
                """, rs -> rs.next() ? mapConversion(rs, 1) : null,
                p.tenantId(), entityType, request.entityId(), field);
        if (stored != null && !request.reconvert()) {
            return stored;
        }

        PolicyRow policy = policies().stream()
                .filter(row -> row.objectName().equals(entityType)).findFirst()
                .orElse(new PolicyRow(entityType, "TODAY", null));
        LocalDate rateDate = "RECORD_DATE".equals(policy.rateBasis())
                ? (request.recordDate() == null ? LocalDate.now() : request.recordDate())
                : LocalDate.now();
        if ("RECORD_DATE".equals(policy.rateBasis()) && request.recordDate() == null) {
            throw new ConflictException(entityType + " converts at its "
                    + (policy.recordDateField() == null ? "record date" : policy.recordDateField())
                    + ". Supply that date so the correct historical rate can be applied.");
        }

        BigDecimal appliedRate;
        UUID rateId = null;
        if (txCurrency.equals(corporate.code())) {
            appliedRate = BigDecimal.ONE;
        } else {
            RateRow rate = rateOn(txCurrency, corporate.code(), rateDate);
            if (rate == null) {
                throw new ConflictException("No %s→%s exchange rate is in force on %s. "
                        .formatted(txCurrency, corporate.code(), rateDate)
                        + "Add a rate covering that date, then save the record again.");
            }
            appliedRate = rate.rate();
            rateId = rate.id();
        }
        BigDecimal corporateAmount = request.transactionAmount()
                .multiply(appliedRate)
                .setScale(WORKING_SCALE, RoundingMode.HALF_UP)
                .setScale(corporate.decimalPlaces(), RoundingMode.HALF_UP);

        if (stored != null) {
            jdbc.update("""
                    update orgdata.money_conversion set is_current = false
                    where tenant_id = ? and id = ?
                    """, p.tenantId(), stored.id());
        }
        UUID id = jdbc.queryForObject("""
                insert into orgdata.money_conversion
                  (tenant_id, entity_type, entity_id, entity_field, transaction_currency,
                   transaction_amount, corporate_currency, corporate_amount, applied_rate,
                   rate_date, rate_id, rate_basis, is_current, converted_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?)
                returning id
                """, UUID.class, p.tenantId(), entityType, request.entityId(), field, txCurrency,
                request.transactionAmount(), corporate.code(), corporateAmount, appliedRate,
                rateDate, rateId, policy.rateBasis(), p.userId());
        if (stored != null) {
            jdbc.update("update orgdata.money_conversion set superseded_by = ? where tenant_id = ? and id = ?",
                    id, p.tenantId(), stored.id());
        }
        audit.recordWithReason(stored == null ? "MONEY_CONVERSION_STORE" : "MONEY_CONVERSION_RECONVERT",
                entityType, request.entityId(),
                "%s %s converted to %s %s at rate %s dated %s".formatted(txCurrency,
                        request.transactionAmount().toPlainString(), corporate.code(),
                        corporateAmount.toPlainString(), appliedRate.toPlainString(), rateDate),
                request.reason(),
                Map.of("transactionCurrency", txCurrency,
                        "transactionAmount", request.transactionAmount().toPlainString(),
                        "corporateCurrency", corporate.code(),
                        "corporateAmount", corporateAmount.toPlainString(),
                        "appliedRate", appliedRate.toPlainString(),
                        "rateDate", rateDate.toString(),
                        "rateBasis", policy.rateBasis(),
                        "recomputed", stored != null));
        return new ConversionRow(id, entityType, request.entityId(), field, txCurrency,
                request.transactionAmount(), corporate.code(), corporateAmount, appliedRate,
                rateDate, policy.rateBasis(), true);
    }

    private CurrencyRow requireCurrency(String code) {
        try {
            return jdbc.queryForObject("""
                    select id, code, name, symbol, decimal_places, is_corporate, active
                    from orgdata.currency where tenant_id = ? and code = ?
                    """, CurrencyService::mapCurrency, TenantContext.get().tenantId(), code);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Currency " + code + " is not configured for this tenant");
        }
    }

    private static CurrencyRow mapCurrency(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new CurrencyRow(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getString("symbol"), rs.getInt("decimal_places"),
                rs.getBoolean("is_corporate"), rs.getBoolean("active"));
    }

    private static RateRow mapRate(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new RateRow(rs.getObject("id", UUID.class), rs.getString("from_currency"),
                rs.getString("to_currency"), rs.getBigDecimal("rate"),
                rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), rs.getString("source"));
    }

    private static ConversionRow mapConversion(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new ConversionRow(rs.getObject("id", UUID.class), rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class), rs.getString("entity_field"),
                rs.getString("transaction_currency"), rs.getBigDecimal("transaction_amount"),
                rs.getString("corporate_currency"), rs.getBigDecimal("corporate_amount"),
                rs.getBigDecimal("applied_rate"), rs.getObject("rate_date", LocalDate.class),
                rs.getString("rate_basis"), rs.getBoolean("is_current"));
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
