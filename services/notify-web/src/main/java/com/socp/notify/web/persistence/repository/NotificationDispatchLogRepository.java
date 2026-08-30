package com.socp.notify.web.persistence.repository;


import com.socp.notify.web.persistence.entity.NotificationDispatchLogEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationDispatchLogRepository extends TenantScopedRepository<NotificationDispatchLogEntity, String> {
    List<NotificationDispatchLogEntity> findByTenantId(String tenantId);
    Optional<NotificationDispatchLogEntity> findByIdAndTenantId(String id, String tenantId);
    List<NotificationDispatchLogEntity> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
