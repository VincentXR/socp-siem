package com.socp.detect.web.persistence.repository;
import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Repository for the durable Detection -> Alert Web hand-off. */
public interface DetectionAlertOutboxRepository extends TenantScopedRepository<DetectionAlertOutboxEntity, String> {
    List<DetectionAlertOutboxEntity> findByTenantId(String tenantId);
    boolean existsByAlertIdAndTenantId(String alertId, String tenantId);
    Optional<DetectionAlertOutboxEntity> findByAlertIdAndTenantId(String alertId, String tenantId);

    List<DetectionAlertOutboxEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);

    List<DetectionAlertOutboxEntity> findByStatusAndUpdatedAtBefore(String status, Instant cutoff);

    List<DetectionAlertOutboxEntity> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(
            String tenantId, String status);

    long countByStatus(String status);

    @Query("select min(e.createdAt) from DetectionAlertOutboxEntity e where e.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(e.updatedAt) from DetectionAlertOutboxEntity e where e.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

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
    @Query("update DetectionAlertOutboxEntity e set e.status = "
            + "case when e.deliveredAt is null then 'PENDING' else 'DELIVERED' end, e.attempts = 0, "
            + "e.nextAttemptAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.alertId = :alertId and e.tenantId = :tenantId and e.status = 'DEAD'")
    int requeueDead(@Param("alertId") String alertId, @Param("tenantId") String tenantId,
                    @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'DISCARDED', "
            + "e.lastError = :reason, e.updatedAt = :now where e.alertId = :alertId "
            + "and e.tenantId = :tenantId and e.status = 'DEAD'")
    int discardDead(@Param("alertId") String alertId, @Param("tenantId") String tenantId,
                    @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = "delete from t_detection_alert_outbox where alert_id in ("
            + "select alert_id from t_detection_alert_outbox where status = 'PUBLISHED' and published_at < :cutoff "
            + "order by published_at asc limit :batchSize)", nativeQuery = true)
    int deletePublishedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = "delete from t_detection_alert_outbox where alert_id in ("
            + "select alert_id from t_detection_alert_outbox where status = 'DISCARDED' and updated_at < :cutoff "
            + "order by updated_at asc limit :batchSize)", nativeQuery = true)
    int deleteDiscardedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);
}
