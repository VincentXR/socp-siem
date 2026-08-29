package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.EntityRiskAlertEntity;

import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.time.Instant;
import java.util.List;

public interface EntityRiskAlertRepository extends TenantScopedRepository<EntityRiskAlertEntity, String> {
    List<EntityRiskAlertEntity> findByTenantId(String tenantId);
    java.util.Optional<EntityRiskAlertEntity> findByStorageIdAndTenantId(String storageId, String tenantId);
    java.util.Optional<EntityRiskAlertEntity> findByTenantIdAndAlertId(String tenantId, String alertId);
    long countByTenantIdAndEntityAndCreatedAtAfter(String tenantId, String entity, Instant cutoff);
}
