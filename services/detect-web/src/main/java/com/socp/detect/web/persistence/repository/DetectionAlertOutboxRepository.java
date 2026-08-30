package com.socp.detect.web.persistence.repository;


import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Repository for the durable Detection -> Alert Web hand-off. */
public interface DetectionAlertOutboxRepository extends TenantScopedRepository<DetectionAlertOutboxEntity, String> {
    List<DetectionAlertOutboxEntity> findByTenantId(String tenantId);
    boolean existsByAlertIdAndTenantId(String alertId, String tenantId);

    List<DetectionAlertOutboxEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);

    List<DetectionAlertOutboxEntity> findByStatusAndUpdatedAtBefore(String status, Instant cutoff);

    @Modifying
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'PROCESSING', e.updatedAt = :now, " +
            "e.attempts = e.attempts + 1 " +
            "where e.alertId = :alertId and e.status = :expectedStatus and e.nextAttemptAt <= :now " +
            "and e.attempts < :maxAttempts")
    int claim(@Param("alertId") String alertId,
              @Param("expectedStatus") String expectedStatus,
              @Param("now") Instant now,
              @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'DEAD', "
            + "e.lastError = coalesce(e.lastError, :reason), e.updatedAt = :now "
            + "where e.status in ('PENDING', 'DELIVERED') and e.attempts >= :maxAttempts")
    int markExhausted(@Param("maxAttempts") int maxAttempts, @Param("reason") String reason,
                      @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'PENDING', e.attempts = 0, "
            + "e.nextAttemptAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.alertId = :alertId and e.status = 'DEAD'")
    int requeueDead(@Param("alertId") String alertId, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("delete from DetectionAlertOutboxEntity e where e.status = 'PUBLISHED' and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
