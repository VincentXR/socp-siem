package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionStateSnapshotEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

public interface DetectionStateSnapshotRepository
        extends TenantScopedRepository<DetectionStateSnapshotEntity, String> {

    Optional<DetectionStateSnapshotEntity> findByTenantIdAndRuleIdAndShardId(
            String tenantId, String ruleId, int shardId);
}
