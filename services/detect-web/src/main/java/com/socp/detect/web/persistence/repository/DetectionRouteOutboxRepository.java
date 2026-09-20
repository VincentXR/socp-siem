package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.DetectionRouteOutboxEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface DetectionRouteOutboxRepository
        extends TenantScopedRepository<DetectionRouteOutboxEntity, String> {

    List<DetectionRouteOutboxEntity>
    findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(String status, Instant now);

    List<DetectionRouteOutboxEntity> findByStatusAndUpdatedAtBefore(String status, Instant cutoff);

    long countByStatus(String status);

    long countByTenantIdAndSourceEventId(String tenantId, String sourceEventId);

    @Modifying
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = 'PROCESSING', "
            + "e.attempts = e.attempts + 1, e.updatedAt = :now "
            + "where e.deliveryId = :id and e.status = 'PENDING' "
            + "and e.nextAttemptAt <= :now and e.attempts < :maxAttempts")
    int claim(@Param("id") String deliveryId, @Param("now") Instant now,
              @Param("maxAttempts") int maxAttempts);
}
