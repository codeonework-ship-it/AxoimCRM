package com.axiom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "contact", schema = "crm")
public class Contact {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "first_name", nullable = false, columnDefinition = "text")
    private String firstName;

    @Column(name = "last_name", nullable = false, columnDefinition = "text")
    private String lastName;

    @Column(columnDefinition = "text")
    private String email;

    @Column(columnDefinition = "text")
    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Contact() {}

    public Contact(UUID tenantId, UUID accountId, String firstName, String lastName, String email, String title) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.accountId = accountId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.title = title;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAccountId() { return accountId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }
}
