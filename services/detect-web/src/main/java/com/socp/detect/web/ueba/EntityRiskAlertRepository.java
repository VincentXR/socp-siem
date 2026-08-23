package com.socp.detect.web.ueba;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

interface EntityRiskAlertRepository extends JpaRepository<EntityRiskAlertEntity, String> {
    java.util.Optional<EntityRiskAlertEntity> findByTenantIdAndAlertId(String tenantId, String alertId);
    long countByTenantIdAndEntityAndCreatedAtAfter(String tenantId, String entity, Instant cutoff);
}
