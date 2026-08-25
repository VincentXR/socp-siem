package com.socp.notify.web.persistence.repository;



import com.socp.notify.web.persistence.store.*;
import com.socp.notify.web.persistence.repository.*;
import com.socp.notify.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, String> {
    Optional<NotificationDeliveryEntity> findByIdAndTenantId(String id, String tenantId);
}
