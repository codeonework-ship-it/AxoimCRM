package com.axiom.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    List<Contact> findByTenantIdOrderByLastNameAscFirstNameAsc(UUID tenantId);

    List<Contact> findByTenantIdAndAccountIdOrderByLastNameAscFirstNameAsc(UUID tenantId, UUID accountId);

    Optional<Contact> findByTenantIdAndId(UUID tenantId, UUID id);
}
