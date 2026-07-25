package com.axiom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "opportunity_contact_role")
public class OpportunityContactRole {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "opportunity_id", nullable = false)
    private UUID opportunityId;

    @Column(name = "contact_id", nullable = false)
    private UUID contactId;

    @Column(nullable = false, columnDefinition = "text")
    private String role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OpportunityContactRole() {}

    public OpportunityContactRole(UUID tenantId, UUID opportunityId, UUID contactId, String role) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.opportunityId = opportunityId;
        this.contactId = contactId;
        this.role = role;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getOpportunityId() { return opportunityId; }
    public UUID getContactId() { return contactId; }
    public String getRole() { return role; }
    public Instant getCreatedAt() { return createdAt; }
}
