package com.socp.hips.web.persistence.repository;


import com.socp.hips.web.persistence.entity.EndpointEventEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface EndpointEventRepository extends TenantScopedRepository<EndpointEventEntity, String> {
    List<EndpointEventEntity> findByTenantId(String tenantId);
    Optional<EndpointEventEntity> findByEventIdAndTenantId(String eventId, String tenantId);

    List<EndpointEventEntity> findTop200ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
