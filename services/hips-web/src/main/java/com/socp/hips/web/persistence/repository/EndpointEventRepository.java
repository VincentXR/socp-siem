package com.socp.hips.web.persistence.repository;


import com.socp.hips.web.persistence.entity.EndpointEventEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface EndpointEventRepository extends TenantScopedRepository<EndpointEventEntity, String> {
    List<EndpointEventEntity> findByTenantId(String tenantId);
    Page<EndpointEventEntity> findByTenantId(String tenantId, Pageable pageable);
    Optional<EndpointEventEntity> findByEventIdAndTenantId(String eventId, String tenantId);
    long countByTenantId(String tenantId);

    List<EndpointEventEntity> findTop200ByTenantIdOrderByReceivedAtDesc(String tenantId);
}
