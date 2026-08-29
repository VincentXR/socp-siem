package com.socp.notify.web.persistence.repository;



import com.socp.notify.web.persistence.store.*;
import com.socp.notify.web.persistence.repository.*;
import com.socp.notify.web.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;
import java.util.List;

public interface NotificationDeliveryRepository extends TenantScopedRepository<NotificationDeliveryEntity, String> {
    List<NotificationDeliveryEntity> findByTenantId(String tenantId);
    Optional<NotificationDeliveryEntity> findByIdAndTenantId(String id, String tenantId);
}
