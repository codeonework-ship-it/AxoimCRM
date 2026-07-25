package com.axiom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "opportunity")
public class Opportunity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "stage_id", nullable = false)
    private UUID stageId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "close_date")
    private LocalDate closeDate;

    @Column(name = "is_closed", nullable = false)
    private boolean closed;

    @Column(name = "is_won")
    private Boolean won;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    protected Opportunity() {}

    public Opportunity(UUID tenantId, String name, UUID accountId, UUID stageId,
                       BigDecimal amount, UUID ownerId, LocalDate closeDate) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.accountId = accountId;
        this.stageId = stageId;
        this.amount = amount == null ? BigDecimal.ZERO : amount;
        this.ownerId = ownerId;
        this.closeDate = closeDate;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Applies a stage transition, deriving closed/won from the stage row. */
    public void moveToStage(PipelineStage stage) {
        this.stageId = stage.getId();
        this.closed = stage.isClosed();
        this.won = stage.isClosed() ? stage.isWon() : null;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public UUID getAccountId() { return accountId; }
    public UUID getStageId() { return stageId; }
    public BigDecimal getAmount() { return amount; }
    public UUID getOwnerId() { return ownerId; }
    public LocalDate getCloseDate() { return closeDate; }
    public boolean isClosed() { return closed; }
    public Boolean getWon() { return won; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
