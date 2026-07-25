package com.axiom.orgdata;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.axiom.tenancy.TenantContext.Principal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-MDM-010 — the decision half of master-data change control.
 *
 * <p>Approval applies the change within the same transaction as the decision, so
 * a request cannot end up marked APPROVED while the change it describes was
 * never written. If applying fails — the payload has since become invalid, a code
 * was taken by someone else — the request is left FAILED with the reason, and the
 * error is returned to the approver rather than swallowed.
 *
 * <p>Segregation of duties: the requester may not approve their own request. A
 * change-control process where one person can do both is a log, not a control.
 */
@Service
public class MasterChangeControlService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;
    private final MasterGovernanceGate gate;
    private final BusinessUnitService businessUnits;
    private final CurrencyService currencies;
    private final QuotaService quotas;

    public MasterChangeControlService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json,
                                     MasterGovernanceGate gate, BusinessUnitService businessUnits,
                                     CurrencyService currencies, QuotaService quotas) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.gate = gate;
        this.businessUnits = businessUnits;
        this.currencies = currencies;
        this.quotas = quotas;
    }

    public record ChangeRequestRow(UUID id, String masterType, String operation, UUID targetId,
                                   String summary, String payload, String status,
                                   String requestedByName, OffsetDateTime requestedAt,
                                   String decidedByName, OffsetDateTime decidedAt,
                                   String decisionReason, UUID appliedEntityId,
                                   String failureReason) {}

    public record DecisionRequest(@Size(max = 500) String reason) {}

    @Transactional(readOnly = true)
    public List<ChangeRequestRow> list(String status) {
        String wanted = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return jdbc.query("""
                select r.id, r.master_type, r.operation, r.target_id, r.summary, r.payload::text,
                       r.status, coalesce(req.display_name, '') as requested_by_name, r.requested_at,
                       coalesce(dec.display_name, '') as decided_by_name, r.decided_at,
                       r.decision_reason, r.applied_entity_id, r.failure_reason
                from orgdata.master_change_request r
                left join identity.app_user req on req.tenant_id = r.tenant_id and req.id = r.requested_by
                left join identity.app_user dec on dec.tenant_id = r.tenant_id and dec.id = r.decided_by
                where r.tenant_id = ? and (? = '' or r.status = ?)
                order by r.requested_at desc
                limit 200
                """, (rs, i) -> new ChangeRequestRow(rs.getObject("id", UUID.class),
                rs.getString("master_type"), rs.getString("operation"),
                rs.getObject("target_id", UUID.class), rs.getString("summary"),
                rs.getString("payload"), rs.getString("status"), rs.getString("requested_by_name"),
                rs.getObject("requested_at", OffsetDateTime.class), rs.getString("decided_by_name"),
                rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decision_reason"),
                rs.getObject("applied_entity_id", UUID.class), rs.getString("failure_reason")),
                TenantContext.get().tenantId(), wanted, wanted);
    }

    @Transactional
    public ChangeRequestRow approve(UUID id, DecisionRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        PendingRequest pending = loadPending(id);
        if (pending.requestedBy().equals(p.userId())) {
            throw new ForbiddenException("You submitted this change, so you cannot approve it. "
                    + "Ask another administrator to review it.");
        }
        JsonNode payload = parse(pending.payload());
        UUID appliedEntityId;
        try {
            appliedEntityId = gate.applying(() -> apply(pending.masterType(), pending.operation(), payload));
        } catch (RuntimeException ex) {
            jdbc.update("""
                    update orgdata.master_change_request
                    set status = 'FAILED', decided_by = ?, decided_at = now(),
                        decision_reason = nullif(?, ''), failure_reason = ?
                    where tenant_id = ? and id = ?
                    """, p.userId(), request == null || request.reason() == null ? "" : request.reason().trim(),
                    ex.getMessage(), p.tenantId(), id);
            audit.recordWithReason("MASTER_CHANGE_FAILED", pending.masterType(), id,
                    "Approved change could not be applied: " + pending.summary(),
                    request == null ? null : request.reason(),
                    Map.of("masterType", pending.masterType(), "failureReason",
                            ex.getMessage() == null ? "" : ex.getMessage()));
            throw ex;
        }
        jdbc.update("""
                update orgdata.master_change_request
                set status = 'APPLIED', decided_by = ?, decided_at = now(),
                    decision_reason = nullif(?, ''), applied_at = now(), applied_entity_id = ?
                where tenant_id = ? and id = ?
                """, p.userId(), request == null || request.reason() == null ? "" : request.reason().trim(),
                appliedEntityId, p.tenantId(), id);
        audit.recordWithReason("MASTER_CHANGE_APPROVED", pending.masterType(), id,
                "Approved and applied: " + pending.summary(),
                request == null ? null : request.reason(),
                Map.of("masterType", pending.masterType(), "operation", pending.operation(),
                        "appliedEntityId", appliedEntityId == null ? "" : appliedEntityId.toString()));
        return byId(id);
    }

    @Transactional
    public ChangeRequestRow reject(UUID id, DecisionRequest request) {
        Principal p = TenantContext.get();
        CrmRole.requireMasterAdmin(p.role());
        PendingRequest pending = loadPending(id);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ConflictException("Rejecting a change needs a reason, so the requester knows "
                    + "what to correct before resubmitting.");
        }
        jdbc.update("""
                update orgdata.master_change_request
                set status = 'REJECTED', decided_by = ?, decided_at = now(), decision_reason = ?
                where tenant_id = ? and id = ?
                """, p.userId(), request.reason().trim(), p.tenantId(), id);
        audit.recordWithReason("MASTER_CHANGE_REJECTED", pending.masterType(), id,
                "Rejected: " + pending.summary(), request.reason(),
                Map.of("masterType", pending.masterType(), "operation", pending.operation()));
        return byId(id);
    }

    @Transactional(readOnly = true)
    public ChangeRequestRow byId(UUID id) {
        return list("").stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Change request not found"));
    }

    private UUID apply(String masterType, String operation, JsonNode payload) {
        return switch (masterType) {
            case BusinessUnitService.MASTER_TYPE -> businessUnits.apply(
                    new BusinessUnitService.BusinessUnitRequest(
                            text(payload, "code"), text(payload, "name"),
                            payload.path("legalEntity").asBoolean(false),
                            uuid(payload, "parentId"), text(payload, "currencyCode"))).id();
            case CurrencyService.CURRENCY_MASTER -> currencies.applyCurrency(
                    new CurrencyService.CurrencyRequest(text(payload, "code"), text(payload, "name"),
                            text(payload, "symbol"),
                            payload.hasNonNull("decimalPlaces") ? payload.get("decimalPlaces").asInt() : 2)).id();
            case CurrencyService.RATE_MASTER -> currencies.applyRate(
                    new CurrencyService.RateRequest(text(payload, "fromCurrency"),
                            text(payload, "toCurrency"),
                            new BigDecimal(text(payload, "rate")),
                            LocalDate.parse(text(payload, "effectiveFrom")),
                            payload.hasNonNull("effectiveTo") ? LocalDate.parse(text(payload, "effectiveTo")) : null,
                            text(payload, "source"))).id();
            case QuotaService.MASTER_TYPE -> quotas.apply(
                    new QuotaService.QuotaRequest(text(payload, "subjectType"),
                            uuid(payload, "subjectId"), text(payload, "subjectLabel"),
                            uuid(payload, "fiscalPeriodId"), text(payload, "measure"),
                            new BigDecimal(text(payload, "targetAmount")),
                            text(payload, "currencyCode"), text(payload, "unitOfMeasure"),
                            text(payload, "changeReason"))).id();
            default -> throw new ConflictException("No apply path is registered for master type "
                    + masterType + ". Remove it from the governed-master registry or add a handler.");
        };
    }

    private record PendingRequest(UUID id, String masterType, String operation, String summary,
                                  String payload, String status, UUID requestedBy) {}

    private PendingRequest loadPending(UUID id) {
        List<PendingRequest> rows = jdbc.query("""
                select id, master_type, operation, summary, payload::text, status, requested_by
                from orgdata.master_change_request
                where tenant_id = ? and id = ?
                """, (rs, i) -> new PendingRequest(rs.getObject("id", UUID.class),
                rs.getString("master_type"), rs.getString("operation"), rs.getString("summary"),
                rs.getString("payload"), rs.getString("status"),
                rs.getObject("requested_by", UUID.class)),
                TenantContext.get().tenantId(), id);
        if (rows.isEmpty()) throw new NotFoundException("Change request not found");
        PendingRequest pending = rows.get(0);
        if (!"PENDING".equals(pending.status())) {
            throw new ConflictException("This change request has already been "
                    + pending.status().toLowerCase(Locale.ROOT)
                    + ". Only a pending request can be decided.");
        }
        return pending;
    }

    private JsonNode parse(String payload) {
        try {
            return json.readTree(payload);
        } catch (JsonProcessingException ex) {
            throw new ConflictException("The stored change payload is not readable and cannot be applied.");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
