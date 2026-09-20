package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionRouteSourceEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

public interface DetectionRouteSourceRepository
        extends TenantScopedRepository<DetectionRouteSourceEntity, String> {

    Optional<DetectionRouteSourceEntity>
    findByTenantIdAndSourceTopicAndSourcePartitionAndSourceOffset(
            String tenantId, String sourceTopic, int sourcePartition, long sourceOffset);
}
