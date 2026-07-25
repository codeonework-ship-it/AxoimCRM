package com.axiom.api;

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

import java.math.BigDecimal;
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

    public LeadService(LeadRepository leads, AccountRepository accounts, ContactRepository contacts,
                       OpportunityRepository opportunities, PipelineStageRepository stages, OutboxWriter outbox,
                       NotificationWriter notifications) {
        this.leads = leads;
        this.accounts = accounts;
        this.contacts = contacts;
        this.opportunities = opportunities;
        this.stages = stages;
        this.outbox = outbox;
        this.notifications = notifications;
    }

    public record ConversionResult(UUID leadId, UUID accountId, UUID contactId, UUID opportunityId) {}

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
        notifications.notifyCurrentUser(
                "SYSTEM", "NORMAL", "Lead conversion complete",
                lead.getFirstName() + " " + lead.getLastName()
                        + " is now linked to account " + account.getName() + ".",
                "/accounts", "You converted this lead.", false);

        return new ConversionResult(lead.getId(), account.getId(), contact.getId(), opportunityId);
    }
}
