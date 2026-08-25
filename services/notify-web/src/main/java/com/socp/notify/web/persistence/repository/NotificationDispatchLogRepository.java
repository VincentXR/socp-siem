package com.socp.notify.web.persistence.repository;



import com.socp.notify.web.persistence.store.*;
import com.socp.notify.web.persistence.repository.*;
import com.socp.notify.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationDispatchLogRepository extends JpaRepository<NotificationDispatchLogEntity, String> {
    List<NotificationDispatchLogEntity> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
