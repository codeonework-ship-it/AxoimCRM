package com.axiom.api;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.domain.Account;
import com.axiom.domain.AccountRepository;
import com.axiom.domain.Contact;
import com.axiom.domain.ContactRepository;
import com.axiom.domain.Lead;
import com.axiom.domain.LeadRepository;
import com.axiom.domain.Opportunity;
import com.axiom.domain.OpportunityRepository;
import com.axiom.domain.PipelineStage;
import com.axiom.domain.PipelineStageRepository;
import com.axiom.outbox.OutboxWriter;
import com.axiom.notifications.NotificationWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class LeadService {

    private final LeadRepository leads;
    private final AccountRepository accounts;
    private final ContactRepository contacts;
    private final OpportunityRepository opportunities;
    private final PipelineStageRepository stages;
    private final OutboxWriter outbox;
    private final NotificationWriter notifications;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public LeadService(LeadRepository leads, AccountRepository accounts, ContactRepository contacts,
                       OpportunityRepository opportunities, PipelineStageRepository stages, OutboxWriter outbox,
                       NotificationWriter notifications, JdbcTemplate jdbc, AuditService audit) {
        this.leads = leads;
        this.accounts = accounts;
        this.contacts = contacts;
        this.opportunities = opportunities;
        this.stages = stages;
        this.outbox = outbox;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ConversionResult(UUID leadId, UUID accountId, UUID contactId, UUID opportunityId) {}
    public record DisqualificationResult(UUID leadId, String status, String reasonCode, LocalDate recycleDate) {}

    /**
     * Atomic lead conversion (FR-LED-011 shape, skeleton scope): account is
     * linked by name if one exists, created otherwise; a contact is always
     * created from the lead; an opportunity is created in the first pipeline
     * stage only when a name for it was supplied. One transaction — the
     * modular-monolith payoff of ADR-006 (no saga), with the lead.converted
     * event written to the outbox in that same transaction (ADR-003).
     */
    @Transactional
    public ConversionResult convert(UUID leadId, String accountName, String opportunityName, BigDecimal amount) {
        if (CrmRole.current(TenantContext.get().role()).readOnly()) {
            throw new ConflictException("Read-only roles cannot convert leads");
        }
        TenantContext.Principal principal = TenantContext.get();
        UUID tenantId = principal.tenantId();

        Lead lead = leads.findByTenantIdAndId(tenantId, leadId)
                .orElseThrow(() -> new NotFoundException("Lead not found: " + leadId));

        if ("CONVERTED".equals(lead.getStatus()) || "DISQUALIFIED".equals(lead.getStatus())) {
            throw new ConflictException("Lead is already " + lead.getStatus().toLowerCase() + " and cannot be converted");
        }

        // Create or link the account.
        String resolvedAccountName = (accountName == null || accountName.isBlank())
                ? lead.getCompany() : accountName.trim();
        Account account = accounts.findByTenantIdAndName(tenantId, resolvedAccountName)
                .orElseGet(() -> accounts.save(new Account(tenantId, resolvedAccountName, null, principal.userId())));

        // Contact from the lead's identity.
        Contact contact = contacts.save(new Contact(
                tenantId, account.getId(), lead.getFirstName(), lead.getLastName(), lead.getEmail(), null));

        // Optional opportunity in the first stage of the pipeline.
        UUID opportunityId = null;
        if (opportunityName != null && !opportunityName.isBlank()) {
            PipelineStage firstStage = stages.findFirstByTenantIdAndDeletedAtIsNullOrderBySortOrderAsc(tenantId)
                    .orElseThrow(() -> new ConflictException("No pipeline stages configured for tenant"));
            Opportunity opp = opportunities.save(new Opportunity(
                    tenantId, opportunityName.trim(), account.getId(), firstStage.getId(),
                    amount, principal.userId(), null));
            opportunityId = opp.getId();
        }

        lead.markConverted(account.getId(), contact.getId(), opportunityId);
        leads.save(lead);

        Map<String, Object> payload = new HashMap<>();
        payload.put("leadId", lead.getId().toString());
        payload.put("accountId", account.getId().toString());
        payload.put("contactId", contact.getId().toString());
        payload.put("opportunityId", opportunityId == null ? null : opportunityId.toString());
        outbox.write("lead", lead.getId(), "lead.converted", payload);
        if (canNotifyCurrentUser(tenantId, principal.userId())) {
            notifications.notifyCurrentUser(
                    "SYSTEM", "NORMAL", "Lead conversion complete",
                    lead.getFirstName() + " " + lead.getLastName()
                            + " is now linked to account " + account.getName() + ".",
                    "/accounts", "You converted this lead.", false);
        }

        return new ConversionResult(lead.getId(), account.getId(), contact.getId(), opportunityId);
    }

    @Transactional
    public DisqualificationResult disqualify(UUID leadId, String reasonCode, String note, LocalDate recycleDate) {
        if (CrmRole.current(TenantContext.get().role()).readOnly()) {
            throw new ConflictException("Read-only roles cannot disqualify leads");
        }
        UUID tenantId = TenantContext.get().tenantId();
        Lead lead = leads.findByTenantIdAndId(tenantId, leadId)
                .orElseThrow(() -> new NotFoundException("Lead not found: " + leadId));
        if ("CONVERTED".equals(lead.getStatus())) {
            throw new ConflictException("Converted leads are read-only and cannot be disqualified");
        }
        if ("DISQUALIFIED".equals(lead.getStatus())) {
            throw new ConflictException("Lead is already disqualified");
        }
        String code = clean(reasonCode);
        if (code == null) throw new ConflictException("Disqualification requires a governed reason code");
        code = code.toUpperCase();
        Integer reasonExists = jdbc.queryForObject("""
                select count(*)
                from reference.value_set s
                join reference.value_set_entry e on e.tenant_id = s.tenant_id and e.value_set_id = s.id
                where s.tenant_id = ? and s.api_name = 'lead_disqualification_reason'
                  and e.code = ? and e.active = true
                """, Integer.class, tenantId, code);
        if (reasonExists == null || reasonExists == 0) {
            throw new ConflictException("Unknown lead disqualification reason: " + code
                    + ". Pick an active value from lead_disqualification_reason.");
        }
        if (recycleDate != null && recycleDate.isBefore(LocalDate.now())) {
            throw new ConflictException("Recycle date cannot be in the past");
        }
        int updated = jdbc.update("""
                update crm.lead
                set status = 'DISQUALIFIED',
                    disqualify_reason = ?,
                    disqualification_reason_code = ?,
                    disqualified_at = now(),
                    recycle_date = ?,
                    updated_at = now()
                where tenant_id = ? and id = ? and deleted_at is null
                """, clean(note) == null ? code : clean(note), code, recycleDate, tenantId, leadId);
        if (updated == 0) throw new NotFoundException("Lead not found: " + leadId);

        audit.recordWithReason("LEAD_DISQUALIFY", "LEAD", leadId,
                "Disqualified lead " + lead.getFirstName() + " " + lead.getLastName(),
                code, Map.of("reasonCode", code, "recycleDate", recycleDate == null ? "" : recycleDate.toString()));
        outbox.write("lead", leadId, "lead.disqualified", Map.of(
                "leadId", leadId.toString(), "reasonCode", code,
                "recycleDate", recycleDate == null ? "" : recycleDate.toString()));
        if (canNotifyCurrentUser(tenantId, TenantContext.get().userId())) {
            notifications.notifyCurrentUser("SYSTEM", "LOW", "Lead disqualified",
                    lead.getFirstName() + " " + lead.getLastName() + " was moved out of the active queue.",
                    "/leads", "You disqualified this lead.", false);
        }
        return new DisqualificationResult(leadId, "DISQUALIFIED", code, recycleDate);
    }

    private boolean canNotifyCurrentUser(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from identity.app_user
                where tenant_id = ? and id = ? and active = true
                """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
