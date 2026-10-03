package com.socp.notify.web.persistence.repository;


import com.socp.notify.web.persistence.entity.NotificationDispatchLogEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationDispatchLogRepository extends TenantScopedRepository<NotificationDispatchLogEntity, String> {
    List<NotificationDispatchLogEntity> findByTenantId(String tenantId);
    Optional<NotificationDispatchLogEntity> findByIdAndTenantId(String id, String tenantId);
    List<NotificationDispatchLogEntity> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);
    @org.springframework.data.jpa.repository.Query("""
            select l from NotificationDispatchLogEntity l where l.tenantId = :tenantId
              and (:status = '' or l.status = :status)
              and (:alarmId = '' or l.alarmId = :alarmId)
              and (:channel = '' or l.channelName = :channel)
            order by l.createdAt desc, l.id desc
            """)
    Page<NotificationDispatchLogEntity> filterPage(
            @org.springframework.data.repository.query.Param("tenantId") String tenantId,
            @org.springframework.data.repository.query.Param("status") String status,
            @org.springframework.data.repository.query.Param("alarmId") String alarmId,
            @org.springframework.data.repository.query.Param("channel") String channel, Pageable pageable);
    Page<NotificationDispatchLogEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
}
