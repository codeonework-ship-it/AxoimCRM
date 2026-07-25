package com.axiom.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PipelineStageRepository extends JpaRepository<PipelineStage, UUID> {

    List<PipelineStage> findByTenantIdAndDeletedAtIsNullOrderBySortOrderAsc(UUID tenantId);

    Optional<PipelineStage> findByTenantIdAndIdAndDeletedAtIsNull(UUID tenantId, UUID id);

    Optional<PipelineStage> findFirstByTenantIdAndDeletedAtIsNullOrderBySortOrderAsc(UUID tenantId);
}
