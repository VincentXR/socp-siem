package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionRouteTopologyEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

public interface DetectionRouteTopologyRepository
        extends TenantScopedRepository<DetectionRouteTopologyEntity, String> {

    Optional<DetectionRouteTopologyEntity> findByTenantIdAndRoutingVersion(
            String tenantId, String routingVersion);
}
