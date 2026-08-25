package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.EntityRiskAlertEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface EntityRiskAlertRepository extends JpaRepository<EntityRiskAlertEntity, String> {
    java.util.Optional<EntityRiskAlertEntity> findByTenantIdAndAlertId(String tenantId, String alertId);
    long countByTenantIdAndEntityAndCreatedAtAfter(String tenantId, String entity, Instant cutoff);
}
