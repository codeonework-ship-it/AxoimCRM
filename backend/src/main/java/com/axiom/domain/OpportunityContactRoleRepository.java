package com.axiom.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OpportunityContactRoleRepository extends JpaRepository<OpportunityContactRole, UUID> {

    boolean existsByTenantIdAndOpportunityIdAndRole(UUID tenantId, UUID opportunityId, String role);

    boolean existsByTenantIdAndOpportunityIdAndContactIdAndRole(UUID tenantId, UUID opportunityId, UUID contactId, String role);
}
