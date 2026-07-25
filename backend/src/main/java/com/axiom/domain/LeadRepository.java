package com.axiom.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    List<Lead> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Lead> findByTenantIdAndId(UUID tenantId, UUID id);
}
