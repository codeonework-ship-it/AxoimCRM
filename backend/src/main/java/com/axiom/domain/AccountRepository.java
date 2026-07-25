package com.axiom.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * RLS does the tenant scoping (ADR-001); explicit tenant_id predicates are kept
 * in derived queries as the belt-and-braces application-level layer.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Account> findByTenantIdAndName(UUID tenantId, String name);
}
