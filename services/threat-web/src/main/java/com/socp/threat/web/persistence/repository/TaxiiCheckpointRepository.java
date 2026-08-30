package com.socp.threat.web.persistence.repository;

import com.socp.threat.web.persistence.entity.TaxiiCheckpointEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

public interface TaxiiCheckpointRepository extends TenantScopedRepository<TaxiiCheckpointEntity, String> {
    java.util.List<TaxiiCheckpointEntity> findByTenantId(String tenantId);

    Optional<TaxiiCheckpointEntity> findByTenantIdAndFeed(String tenantId, String feed);
}
