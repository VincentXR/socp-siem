package com.socp.hips.collect.persistence.repository;


import com.socp.hips.collect.persistence.entity.HipsEventEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface HipsEventRepository extends TenantScopedRepository<HipsEventEntity, String> {
    List<HipsEventEntity> findByTenantId(String tenantId);
    Optional<HipsEventEntity> findByIdAndTenantId(String id, String tenantId);
    List<HipsEventEntity> findTop200ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
