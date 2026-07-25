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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-009 — quotas by user, team, territory and fiscal period, in revenue or
 * quantity, versioned, with every change audited.
 *
 * <p>A quota is never updated in place. Changing a target writes a new version
 * row and demotes the previous one, so attainment reporting can name the version
 * it used (US-E03-07) instead of quietly reporting against a number that has
 * since moved. A reason is required once the period has begun, because "why did
 * the number change mid-quarter" is the question that always gets asked and
 * almost never has an answer on record.
 */
@Service
public class QuotaService {

    static final String MASTER_TYPE = "QUOTA";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MasterGovernanceGate gate;

    public QuotaService(JdbcTemplate jdbc, AuditService audit, MasterGovernanceGate gate) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.gate = gate;
    }

    public record QuotaRow(UUID id, String subjectType, UUID subjectId, String subjectLabel,
                           UUID fiscalPeriodId, String periodLabel, java.time.LocalDate periodStart,
                           java.time.LocalDate periodEnd, String measure, BigDecimal targetAmount,
                           String currencyCode, String unitOfMeasure, int versionNo,
                           boolean current, UUID supersedesId, String changeReason,
                           OffsetDateTime createdAt, String createdByName) {}

    public record QuotaRequest(
            @NotBlank @Pattern(regexp = "USER|TEAM|TERRITORY") String subjectType,
            @NotNull UUID subjectId,
            @NotBlank @Size(max = 160) String subjectLabel,
            @NotNull UUID fiscalPeriodId,
            @NotBlank @Pattern(regexp = "REVENUE|QUANTITY") String measure,
            @NotNull @DecimalMin(value = "0", message = "A quota target cannot be negative")
            BigDecimal targetAmount,
            String currencyCode,
            String unitOfMeasure,
            String changeReason) {}

    @Transactional(readOnly = true)
    public List<QuotaRow> list(String subjectType, UUID fiscalPeriodId, boolean includeSuperseded) {
        String type = subjectType == null ? "" : subjectType.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("""
                select q.id, q.subject_type, q.subject_id, q.subject_label, q.fiscal_period_id,
                       fp.label as period_label, fp.start_date, fp.end_date, q.measure,
                       q.target_amount, q.currency_code, q.unit_of_measure, q.version_no,
                       q.is_current, q.supersedes_id, q.change_reason, q.created_at,
                       coalesce(u.display_name, '') as created_by_name
                from orgdata.quota q
                join orgdata.fiscal_period fp on fp.tenant_id = q.tenant_id and fp.id = q.fiscal_period_id
                left join identity.app_user u on u.tenant_id = q.tenant_id and u.id = q.created_by
                where q.tenant_id = ?
                  and (? = '' or q.subject_type = ?)
                  and (?::uuid is null or q.fiscal_period_id = ?::uuid)
                  and (? = true or q.is_current)
                order by fp.start_date, q.subject_label, q.measure, q.version_no desc
                """, QuotaService::map, TenantContext.get().tenantId(), type, type,
                fiscalPeriodId, fiscalPeriodId, includeSuperseded);
    }

    /** Every version of one quota line, newest first — the change history for FR-MDM-009. */
    @Transactional(readOnly = true)
    public List<QuotaRow> history(UUID quotaId) {
        QuotaRow anchor = byId(quotaId);
        return jdbc.query("""
                select q.id, q.subject_type, q.subject_id, q.subject_label, q.fiscal_period_id,
                       fp.label as period_label, fp.start_date, fp.end_date, q.measure,
                       q.target_amount, q.currency_code, q.unit_of_measure, q.version_no,
                       q.is_current, q.supersedes_id, q.change_reason, q.created_at,
                       coalesce(u.display_name, '') as created_by_name
                from orgdata.quota q
                join orgdata.fiscal_period fp on fp.tenant_id = q.tenant_id and fp.id = q.fiscal_period_id
                left join identity.app_user u on u.tenant_id = q.tenant_id and u.id = q.created_by
                where q.tenant_id = ? and q.subject_type = ? and q.subject_id = ?
                  and q.fiscal_period_id = ? and q.measure = ?
                order by q.version_no desc
                """, QuotaService::map, TenantContext.get().tenantId(), anchor.subjectType(),
                anchor.subjectId(), anchor.fiscalPeriodId(), anchor.measure());
    }

    @Transactional
    public Submission<QuotaRow> save(QuotaRequest request) {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
        validate(request);
        if (gate.gated(MASTER_TYPE)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("subjectType", request.subjectType());
            payload.put("subjectId", request.subjectId().toString());
            payload.put("subjectLabel", request.subjectLabel().trim());
            payload.put("fiscalPeriodId", request.fiscalPeriodId().toString());
            payload.put("measure", request.measure());
            payload.put("targetAmount", request.targetAmount().toPlainString());
            payload.put("currencyCode", request.currencyCode());
            payload.put("unitOfMeasure", request.unitOfMeasure());
            payload.put("changeReason", request.changeReason());
            UUID changeId = gate.enqueue(MASTER_TYPE, "UPDATE", null,
                    "Set %s quota for %s".formatted(request.measure().toLowerCase(Locale.ROOT),
                            request.subjectLabel().trim()), payload);
            return Submission.pending(changeId, "Quota");
        }
        return Submission.applied(apply(request));
    }

    @Transactional
    public QuotaRow apply(QuotaRequest request) {
        Principal p = TenantContext.get();
        validate(request);
        String measure = request.measure().toUpperCase(Locale.ROOT);
        String subjectType = request.subjectType().toUpperCase(Locale.ROOT);

        PeriodFact period = periodFact(request.fiscalPeriodId());
        QuotaRow existing = jdbc.query("""
                select q.id, q.subject_type, q.subject_id, q.subject_label, q.fiscal_period_id,
                       fp.label as period_label, fp.start_date, fp.end_date, q.measure,
                       q.target_amount, q.currency_code, q.unit_of_measure, q.version_no,
                       q.is_current, q.supersedes_id, q.change_reason, q.created_at, '' as created_by_name
                from orgdata.quota q
                join orgdata.fiscal_period fp on fp.tenant_id = q.tenant_id and fp.id = q.fiscal_period_id
                where q.tenant_id = ? and q.subject_type = ? and q.subject_id = ?
                  and q.fiscal_period_id = ? and q.measure = ? and q.is_current
                """, rs -> rs.next() ? map(rs, 1) : null, p.tenantId(), subjectType,
                request.subjectId(), request.fiscalPeriodId(), measure);

        boolean periodStarted = !period.startDate().isAfter(java.time.LocalDate.now());
        if (existing != null && periodStarted
                && (request.changeReason() == null || request.changeReason().isBlank())) {
            throw new ConflictException(("The %s period has already begun, so changing this quota needs a "
                    + "reason on record. Resubmit with changeReason explaining why the target moved from "
                    + "%s to %s.").formatted(period.label(), existing.targetAmount().toPlainString(),
                    request.targetAmount().toPlainString()));
        }

        int nextVersion = existing == null ? 1 : existing.versionNo() + 1;
        if (existing != null) {
            jdbc.update("update orgdata.quota set is_current = false where tenant_id = ? and id = ?",
                    p.tenantId(), existing.id());
        }
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into orgdata.quota
                      (tenant_id, subject_type, subject_id, subject_label, fiscal_period_id, measure,
                       target_amount, currency_code, unit_of_measure, version_no, is_current,
                       supersedes_id, change_reason, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, nullif(?, ''), nullif(?, ''), ?, true, ?, nullif(?, ''), ?)
                    returning id
                    """, UUID.class, p.tenantId(), subjectType, request.subjectId(),
                    request.subjectLabel().trim(), request.fiscalPeriodId(), measure,
                    request.targetAmount(),
                    request.currencyCode() == null ? "" : request.currencyCode().trim().toUpperCase(Locale.ROOT),
                    request.unitOfMeasure() == null ? "" : request.unitOfMeasure().trim(),
                    nextVersion, existing == null ? null : existing.id(),
                    request.changeReason() == null ? "" : request.changeReason().trim(), p.userId());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("That fiscal period does not exist in this tenant, "
                    + "or the quota conflicts with an existing current version. Reload and retry.");
        }

        // FR-MDM-009: "an audit of every change". Before and after are both on the
        // event, so attainment reporting and a dispute can both be answered from
        // the audit trail alone.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("subjectType", subjectType);
        details.put("subjectLabel", request.subjectLabel().trim());
        details.put("periodLabel", period.label());
        details.put("measure", measure);
        details.put("newTarget", request.targetAmount().toPlainString());
        details.put("previousTarget", existing == null ? "" : existing.targetAmount().toPlainString());
        details.put("versionNo", nextVersion);
        details.put("previousVersionNo", existing == null ? "" : existing.versionNo());
        details.put("periodAlreadyStarted", periodStarted);
        audit.recordWithReason(existing == null ? "QUOTA_CREATE" : "QUOTA_CHANGE", MASTER_TYPE, id,
                existing == null
                        ? "Created %s quota %s for %s in %s".formatted(measure.toLowerCase(Locale.ROOT),
                                request.targetAmount().toPlainString(), request.subjectLabel().trim(), period.label())
                        : "Changed %s quota for %s in %s from %s to %s (version %d)".formatted(
                                measure.toLowerCase(Locale.ROOT), request.subjectLabel().trim(), period.label(),
                                existing.targetAmount().toPlainString(),
                                request.targetAmount().toPlainString(), nextVersion),
                request.changeReason(), details);
        return byId(id);
    }

    @Transactional(readOnly = true)
    public QuotaRow byId(UUID id) {
        List<QuotaRow> rows = jdbc.query("""
                select q.id, q.subject_type, q.subject_id, q.subject_label, q.fiscal_period_id,
                       fp.label as period_label, fp.start_date, fp.end_date, q.measure,
                       q.target_amount, q.currency_code, q.unit_of_measure, q.version_no,
                       q.is_current, q.supersedes_id, q.change_reason, q.created_at,
                       coalesce(u.display_name, '') as created_by_name
                from orgdata.quota q
                join orgdata.fiscal_period fp on fp.tenant_id = q.tenant_id and fp.id = q.fiscal_period_id
                left join identity.app_user u on u.tenant_id = q.tenant_id and u.id = q.created_by
                where q.tenant_id = ? and q.id = ?
                """, QuotaService::map, TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("Quota not found");
        return rows.get(0);
    }

    private void validate(QuotaRequest request) {
        String measure = request.measure() == null ? "" : request.measure().toUpperCase(Locale.ROOT);
        if ("REVENUE".equals(measure) && (request.currencyCode() == null || request.currencyCode().isBlank())) {
            throw new ConflictException("A revenue quota needs a currency. "
                    + "Supply currencyCode so attainment can be compared like for like.");
        }
        if ("QUANTITY".equals(measure) && (request.unitOfMeasure() == null || request.unitOfMeasure().isBlank())) {
            throw new ConflictException("A quantity quota needs a unit of measure, "
                    + "for example units, seats or tonnes.");
        }
        if (request.targetAmount() != null && request.targetAmount().signum() < 0) {
            throw new ConflictException("A quota target cannot be negative.");
        }
    }

    private record PeriodFact(String label, java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    private PeriodFact periodFact(UUID fiscalPeriodId) {
        List<PeriodFact> rows = jdbc.query("""
                select label, start_date, end_date from orgdata.fiscal_period
                where tenant_id = ? and id = ?
                """, (rs, i) -> new PeriodFact(rs.getString("label"),
                rs.getObject("start_date", java.time.LocalDate.class),
                rs.getObject("end_date", java.time.LocalDate.class)),
                TenantContext.get().tenantId(), fiscalPeriodId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Fiscal period not found. Generate the fiscal year first.");
        }
        return rows.get(0);
    }

    private static QuotaRow map(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new QuotaRow(rs.getObject("id", UUID.class), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getString("subject_label"),
                rs.getObject("fiscal_period_id", UUID.class), rs.getString("period_label"),
                rs.getObject("start_date", java.time.LocalDate.class),
                rs.getObject("end_date", java.time.LocalDate.class), rs.getString("measure"),
                rs.getBigDecimal("target_amount"), rs.getString("currency_code"),
                rs.getString("unit_of_measure"), rs.getInt("version_no"), rs.getBoolean("is_current"),
                rs.getObject("supersedes_id", UUID.class), rs.getString("change_reason"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by_name"));
    }
}
