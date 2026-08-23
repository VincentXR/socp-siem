package com.socp.notify.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, String> {
    Optional<NotificationDeliveryEntity> findByIdAndTenantId(String id, String tenantId);
}
