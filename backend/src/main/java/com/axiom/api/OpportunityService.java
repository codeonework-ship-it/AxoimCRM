package com.axiom.api;

import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.domain.Opportunity;
import com.axiom.domain.OpportunityContactRole;
import com.axiom.domain.OpportunityContactRoleRepository;
import com.axiom.domain.OpportunityRepository;
import com.axiom.domain.PipelineStage;
import com.axiom.domain.PipelineStageRepository;
import com.axiom.outbox.OutboxWriter;
import com.axiom.notifications.NotificationWriter;
import com.axiom.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OpportunityService {

    private static final String ECONOMIC_BUYER = "ECONOMIC_BUYER";
    private static final Set<String> VALID_ROLES =
            Set.of(ECONOMIC_BUYER, "CHAMPION", "TECHNICAL_EVALUATOR", "INFLUENCER", "BLOCKER");

    private final OpportunityRepository opportunities;
    private final PipelineStageRepository stages;
    private final OpportunityContactRoleRepository roles;
    private final OutboxWriter outbox;
    private final NotificationWriter notifications;

    public OpportunityService(OpportunityRepository opportunities, PipelineStageRepository stages,
                              OpportunityContactRoleRepository roles, OutboxWriter outbox,
                              NotificationWriter notifications) {
        this.opportunities = opportunities;
        this.stages = stages;
        this.roles = roles;
        this.outbox = outbox;
        this.notifications = notifications;
    }

    /**
     * Stage transition with THE stage gate (FR-OPP-003): a stage flagged
     * requires_economic_buyer refuses entry until an ECONOMIC_BUYER contact
     * role is recorded on the opportunity. The 409 names the unmet criterion.
     */
    @Transactional
    public void changeStage(UUID opportunityId, UUID targetStageId) {
        UUID tenantId = TenantContext.get().tenantId();

        Opportunity opp = opportunities.findByTenantIdAndId(tenantId, opportunityId)
                .orElseThrow(() -> new NotFoundException("Opportunity not found: " + opportunityId));
        PipelineStage target = stages.findByTenantIdAndIdAndDeletedAtIsNull(tenantId, targetStageId)
                .orElseThrow(() -> new NotFoundException("Pipeline stage not found: " + targetStageId));
        PipelineStage from = stages.findByTenantIdAndIdAndDeletedAtIsNull(tenantId, opp.getStageId())
                .orElseThrow(() -> new NotFoundException("Current stage not found: " + opp.getStageId()));

        if (target.getId().equals(from.getId())) {
            return; // no-op move
        }

        if (target.isRequiresEconomicBuyer()
                && !roles.existsByTenantIdAndOpportunityIdAndRole(tenantId, opportunityId, ECONOMIC_BUYER)) {
            throw new ConflictException(
                    "Stage gate: an Economic Buyer contact role is required to enter " + target.getName());
        }

        opp.moveToStage(target);
        opportunities.save(opp);

        outbox.write("opportunity", opp.getId(), "opportunity.stage-changed", Map.of(
                "opportunityId", opp.getId().toString(),
                "from", Map.of("stageId", from.getId().toString(), "name", from.getName()),
                "to", Map.of("stageId", target.getId().toString(), "name", target.getName())));
        notifications.notifyCurrentUser(
                "SYSTEM", "LOW", "Opportunity advanced",
                opp.getName() + " moved from " + from.getName() + " to " + target.getName() + ".",
                "/pipeline", "You changed this opportunity's stage.", false);
    }

    @Transactional
    public UUID addContactRole(UUID opportunityId, UUID contactId, String role) {
        UUID tenantId = TenantContext.get().tenantId();

        if (role == null || !VALID_ROLES.contains(role)) {
            throw new ConflictException("Unknown contact role: " + role + " (expected one of " + VALID_ROLES + ")");
        }
        opportunities.findByTenantIdAndId(tenantId, opportunityId)
                .orElseThrow(() -> new NotFoundException("Opportunity not found: " + opportunityId));
        if (roles.existsByTenantIdAndOpportunityIdAndContactIdAndRole(tenantId, opportunityId, contactId, role)) {
            throw new ConflictException("This contact already holds role " + role + " on the opportunity");
        }

        OpportunityContactRole saved = roles.save(
                new OpportunityContactRole(tenantId, opportunityId, contactId, role));
        return saved.getId();
    }
}
