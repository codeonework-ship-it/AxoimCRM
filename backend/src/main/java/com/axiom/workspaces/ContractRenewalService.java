package com.axiom.workspaces;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Idempotent renewal draft generation for FR-CTR-006. */
@Service
public class ContractRenewalService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxWriter outbox;

    public ContractRenewalService(JdbcTemplate jdbc, AuditService audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
    }

    public record RenewalRequest(@NotBlank @Size(max = 1000) String rationale) {}
    public record RenewalResult(UUID planId, UUID sourceContractId, UUID generatedContractId,
                                String generatedContractNumber, LocalDate startDate, LocalDate endDate,
                                int subscriptionsCopied, boolean alreadyGenerated, String message) {}
    private record Contract(UUID id, String number, UUID accountId, UUID opportunityId, UUID quoteId,
                            UUID ownerId, String title, String status, LocalDate start, LocalDate end,
                            java.math.BigDecimal value, boolean autoRenew) {}

    @Transactional
    public RenewalResult prepare(UUID contractId, RenewalRequest request) {
        requireWrite();
        RenewalResult existing = existing(contractId);
        if (existing != null) return existing;
        Contract source = lock(contractId);
        if (!List.of("ACTIVE", "EXPIRING", "EXPIRED").contains(source.status())) {
            throw new ConflictException("Only active, expiring or expired contracts can create a renewal draft.");
        }
        RenewalWindow window = renewalWindow(source.start(), source.end());
        LocalDate start = window.start();
        LocalDate end = window.end();
        UUID planId = UUID.randomUUID();
        String rationale = request.rationale().trim();
        try {
            jdbc.update("""
                    insert into contracting.renewal_plan
                      (id, tenant_id, source_contract_id, status, proposed_start_date,
                       proposed_end_date, proposed_value, owner_id, rationale, created_by)
                    values (?, ?, ?, 'PLANNED', ?, ?, ?, ?, ?, ?)
                    """, planId, tenantId(), source.id(), start, end, source.value(),
                    source.ownerId(), rationale, userId());
        } catch (DuplicateKeyException race) {
            RenewalResult winner = existing(contractId);
            if (winner != null) return winner;
            throw race;
        }
        UUID generatedId = UUID.randomUUID();
        String generatedNumber = source.number() + "-R-"
                + planId.toString().replace("-", "").substring(0, 6).toUpperCase();
        jdbc.update("""
                insert into contracting.contract_record
                  (id, tenant_id, contract_number, account_id, opportunity_id, quote_id, owner_id,
                   title, status, start_date, end_date, renewal_notice_date, total_contract_value,
                   auto_renew, predecessor_contract_id, renewal_plan_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
                """, generatedId, tenantId(), generatedNumber, source.accountId(), source.opportunityId(),
                source.quoteId(), source.ownerId(), source.title() + " — Renewal", start, end,
                start.minusDays(60), source.value(), source.autoRenew(), source.id(), planId);
        int copied = jdbc.update("""
                insert into contracting.subscription
                  (tenant_id, contract_id, account_id, product_code, product_name, status,
                   start_date, end_date, quantity, recurring_amount, billing_frequency)
                select tenant_id, ?, account_id, product_code, product_name, 'PENDING_RENEWAL',
                       ?, ?, quantity, recurring_amount, billing_frequency
                from contracting.subscription
                where tenant_id = ? and contract_id = ? and status not in ('CANCELLED','EXPIRED')
                """, generatedId, start, end, tenantId(), source.id());
        jdbc.update("""
                update contracting.renewal_plan
                set status = 'GENERATED', generated_contract_id = ?, generated_at = now()
                where tenant_id = ? and id = ?
                """, generatedId, tenantId(), planId);
        Map<String, Object> evidence = Map.of("sourceContract", source.number(),
                "generatedContract", generatedNumber, "subscriptionsCopied", copied,
                "startDate", start.toString(), "endDate", end.toString(), "rationale", rationale);
        audit.recordWithReason("CONTRACT_RENEWAL_PREPARED", "CONTRACT", generatedId,
                "Prepared renewal " + generatedNumber, rationale, evidence);
        outbox.write("contract", generatedId, "contract.renewal-prepared", evidence);
        return new RenewalResult(planId, source.id(), generatedId, generatedNumber, start, end,
                copied, false, "Renewal draft created without changing the active contract.");
    }

    private RenewalResult existing(UUID sourceId) {
        List<RenewalResult> rows = jdbc.query("""
                select p.id, p.source_contract_id, p.generated_contract_id, c.contract_number,
                       p.proposed_start_date, p.proposed_end_date,
                       (select count(*) from contracting.subscription s
                        where s.tenant_id = p.tenant_id and s.contract_id = p.generated_contract_id) copied
                from contracting.renewal_plan p
                join contracting.contract_record c
                  on c.tenant_id = p.tenant_id and c.id = p.generated_contract_id
                where p.tenant_id = ? and p.source_contract_id = ? and p.status = 'GENERATED'
                """, (rs, i) -> new RenewalResult(rs.getObject("id", UUID.class),
                rs.getObject("source_contract_id", UUID.class), rs.getObject("generated_contract_id", UUID.class),
                rs.getString("contract_number"), rs.getObject("proposed_start_date", LocalDate.class),
                rs.getObject("proposed_end_date", LocalDate.class), rs.getInt("copied"), true,
                "This renewal was already generated; returning the existing draft."), tenantId(), sourceId);
        return rows.stream().findFirst().orElse(null);
    }

    private Contract lock(UUID id) {
        List<Contract> rows = jdbc.query("""
                select id, contract_number, account_id, opportunity_id, quote_id, owner_id, title,
                       status, start_date, end_date, total_contract_value, auto_renew
                from contracting.contract_record
                where tenant_id = ? and id = ? and deleted_at is null for update
                """, (rs, i) -> new Contract(rs.getObject("id", UUID.class), rs.getString("contract_number"),
                rs.getObject("account_id", UUID.class), rs.getObject("opportunity_id", UUID.class),
                rs.getObject("quote_id", UUID.class), rs.getObject("owner_id", UUID.class),
                rs.getString("title"), rs.getString("status"), rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class), rs.getBigDecimal("total_contract_value"),
                rs.getBoolean("auto_renew")), tenantId(), id);
        return rows.stream().findFirst().orElseThrow(() -> new NotFoundException("Contract not found"));
    }

    private void requireWrite() {
        if (CrmRole.current(TenantContext.get().role()).readOnly()) {
            throw new ForbiddenException("Your role can review contracts but cannot prepare renewals.");
        }
    }
    record RenewalWindow(LocalDate start, LocalDate end) {}
    static RenewalWindow renewalWindow(LocalDate sourceStart, LocalDate sourceEnd) {
        long termDays = Math.max(0, ChronoUnit.DAYS.between(sourceStart, sourceEnd));
        LocalDate start = sourceEnd.plusDays(1);
        return new RenewalWindow(start, start.plusDays(termDays));
    }
    private static UUID tenantId() { return TenantContext.get().tenantId(); }
    private static UUID userId() { return TenantContext.get().userId(); }
}
