package com.axiom.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "pipeline_stage")
public class PipelineStage {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_closed", nullable = false)
    private boolean closed;

    @Column(name = "is_won", nullable = false)
    private boolean won;

    @Column(name = "requires_economic_buyer", nullable = false)
    private boolean requiresEconomicBuyer;

    protected PipelineStage() {}

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
    public boolean isClosed() { return closed; }
    public boolean isWon() { return won; }
    public boolean isRequiresEconomicBuyer() { return requiresEconomicBuyer; }
}
