package com.socp.soar.web.persistence.repository;

import com.socp.soar.web.persistence.entity.ScheduledPlaybookRunEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

public interface ScheduledPlaybookRunRepository extends TenantScopedRepository<ScheduledPlaybookRunEntity, String> {
    java.util.List<ScheduledPlaybookRunEntity> findByTenantId(String tenantId);
    Optional<ScheduledPlaybookRunEntity> findByIdAndTenantId(String id, String tenantId);
}
