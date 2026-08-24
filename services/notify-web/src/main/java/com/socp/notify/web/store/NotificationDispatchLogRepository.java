package com.socp.notify.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationDispatchLogRepository extends JpaRepository<NotificationDispatchLogEntity, String> {
    List<NotificationDispatchLogEntity> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
