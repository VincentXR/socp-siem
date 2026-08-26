package com.socp.ai.persistence.repository;

import com.socp.ai.persistence.entity.InvestigationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvestigationRepository extends JpaRepository<InvestigationEntity, String> {
    Optional<InvestigationEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<InvestigationEntity> findByTenantIdAndAlertId(String tenantId, String alertId);
}
