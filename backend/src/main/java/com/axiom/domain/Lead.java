package com.axiom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lead", schema = "crm")
public class Lead {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "first_name", nullable = false, columnDefinition = "text")
    private String firstName;

    @Column(name = "last_name", nullable = false, columnDefinition = "text")
    private String lastName;

    @Column(nullable = false, columnDefinition = "text")
    private String company;

    @Column(columnDefinition = "text")
    private String email;

    @Column(nullable = false, columnDefinition = "text")
    private String status = "NEW";

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "converted_account_id")
    private UUID convertedAccountId;

    @Column(name = "converted_contact_id")
    private UUID convertedContactId;

    @Column(name = "converted_opportunity_id")
    private UUID convertedOpportunityId;

    @Column(name = "disqualify_reason", columnDefinition = "text")
    private String disqualifyReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Lead() {}

    public void markConverted(UUID accountId, UUID contactId, UUID opportunityId) {
        this.status = "CONVERTED";
        this.convertedAccountId = accountId;
        this.convertedContactId = contactId;
        this.convertedOpportunityId = opportunityId;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getCompany() { return company; }
    public String getEmail() { return email; }
    public String getStatus() { return status; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getConvertedAccountId() { return convertedAccountId; }
    public UUID getConvertedContactId() { return convertedContactId; }
    public UUID getConvertedOpportunityId() { return convertedOpportunityId; }
    public String getDisqualifyReason() { return disqualifyReason; }
    public Instant getCreatedAt() { return createdAt; }
}
