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

    List<DetectionAlertOutboxEntity> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(
            String tenantId, String status);

    long countByStatus(String status);

    @Query("select min(e.createdAt) from DetectionAlertOutboxEntity e where e.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(e.updatedAt) from DetectionAlertOutboxEntity e where e.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'PROCESSING', e.updatedAt = :now, " +
            "e.attempts = e.attempts + 1, e.claimToken = :token " +
            "where e.alertId = :alertId and e.status = :expectedStatus and e.nextAttemptAt <= :now " +
            "and e.attempts = :expectedAttempts and e.attempts < :maxAttempts")
    int claim(@Param("alertId") String alertId,
              @Param("expectedStatus") String expectedStatus,
              @Param("expectedAttempts") int expectedAttempts,
              @Param("token") String token,
              @Param("now") Instant now,
              @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'PUBLISHED', e.claimToken = null, "
            + "e.deliveredAt = :deliveredAt, e.publishedAt = :now, e.updatedAt = :now, "
            + "e.nextAttemptAt = :now, e.lastError = null "
            + "where e.alertId = :alertId and e.status = 'PROCESSING' and e.claimToken = :token")
    int markPublished(@Param("alertId") String alertId, @Param("token") String token,
                      @Param("deliveredAt") Instant deliveredAt, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = :status, e.claimToken = null, "
            + "e.deliveredAt = :deliveredAt, e.updatedAt = :now, e.nextAttemptAt = :nextAttemptAt, "
            + "e.lastError = :error "
            + "where e.alertId = :alertId and e.status = 'PROCESSING' and e.claimToken = :token")
    int markFailed(@Param("alertId") String alertId, @Param("token") String token,
                   @Param("status") String status, @Param("deliveredAt") Instant deliveredAt,
                   @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error,
                   @Param("now") Instant now);

    // Keep the locking CTE: it fixes the candidates once for the entire update
    // instead of allowing a locking subquery to be re-evaluated beyond its limit.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_detection_alert_outbox set claim_token = null, "
            + "status = case when attempts >= :maxAttempts then 'DEAD' "
            + "when delivered_at is null then 'PENDING' else 'DELIVERED' end, "
            + "updated_at = :now, next_attempt_at = :now, last_error = 'alert delivery lease expired' "
            + "where alert_id in (with candidates as (select alert_id from t_detection_alert_outbox "
            + "where status = 'PROCESSING' and updated_at < :cutoff "
            + "order by updated_at, alert_id limit :batchSize for update skip locked) "
            + "select alert_id from candidates) and status = 'PROCESSING' and updated_at < :cutoff",
            nativeQuery = true)
    int recoverStaleBatch(@Param("cutoff") Instant cutoff, @Param("now") Instant now,
                          @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_detection_alert_outbox set status = 'DEAD', claim_token = null, "
            + "last_error = coalesce(last_error, 'retry limit reached'), updated_at = :now "
            + "where alert_id in (with candidates as (select alert_id from t_detection_alert_outbox "
            + "where status in ('PENDING', 'DELIVERED') and attempts >= :maxAttempts "
            + "order by updated_at, alert_id limit :batchSize for update skip locked) "
            + "select alert_id from candidates) "
            + "and status in ('PENDING', 'DELIVERED') and attempts >= :maxAttempts", nativeQuery = true)
    int markExhaustedBatch(@Param("maxAttempts") int maxAttempts, @Param("now") Instant now,
                           @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = "
            + "case when e.deliveredAt is null then 'PENDING' else 'DELIVERED' end, e.attempts = 0, "
            + "e.nextAttemptAt = :now, e.lastError = null, e.updatedAt = :now, e.claimToken = null "
            + "where e.alertId = :alertId and e.tenantId = :tenantId and e.status = 'DEAD'")
    int requeueDead(@Param("alertId") String alertId, @Param("tenantId") String tenantId,
                    @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'DISCARDED', e.claimToken = null, "
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
