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

    long countByStatus(String status);

    long countByTenantIdAndSourceEventId(String tenantId, String sourceEventId);

    List<DetectionRouteOutboxEntity> findByTenantIdAndSourceEventIdOrderByDeliveryIdAsc(
            String tenantId, String sourceEventId);

    java.util.Optional<DetectionRouteOutboxEntity> findByTenantIdAndDeliveryId(
            String tenantId, String deliveryId);

    List<DetectionRouteOutboxEntity> findBySourceTopicAndSourcePartitionAndSourceOffsetOrderByDeliveryIdAsc(
            String sourceTopic, int sourcePartition, long sourceOffset);

    @Modifying
    @Transactional
    @Query(value = "delete from t_detection_route_outbox where delivery_id in ("
            + "select delivery_id from t_detection_route_outbox "
            + "where status = 'PUBLISHED' and published_at < :cutoff "
            + "order by published_at asc limit :batchSize)", nativeQuery = true)
    int deletePublishedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "delete from t_detection_route_outbox where delivery_id in ("
            + "select delivery_id from t_detection_route_outbox "
            + "where status = 'DEAD' and updated_at < :cutoff "
            + "order by updated_at asc limit :batchSize)", nativeQuery = true)
    int deleteDeadBatchBefore(@Param("cutoff") Instant cutoff,
                              @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = 'PROCESSING', "
            + "e.attempts = e.attempts + 1, e.updatedAt = :now "
            + "where e.deliveryId = :id and e.status = 'PENDING' "
            + "and e.nextAttemptAt <= :now and e.attempts = :expectedAttempts and e.attempts < :maxAttempts")
    int claim(@Param("id") String deliveryId, @Param("now") Instant now,
              @Param("expectedAttempts") int expectedAttempts, @Param("maxAttempts") int maxAttempts);

    // Attempts are monotonic for this delivery's lifetime and fence late owners.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = 'PUBLISHED', "
            + "e.deliveryPartition = :partition, e.deliveryOffset = :offset, "
            + "e.publishedAt = :now, e.updatedAt = :now, e.lastError = null "
            + "where e.deliveryId = :id and e.status = 'PROCESSING' and e.attempts = :attempt")
    int markPublished(@Param("id") String deliveryId, @Param("attempt") int attempt,
                      @Param("partition") int partition, @Param("offset") long offset,
                      @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = :status, "
            + "e.nextAttemptAt = :nextAttemptAt, e.updatedAt = :now, e.lastError = :error "
            + "where e.deliveryId = :id and e.status = 'PROCESSING' and e.attempts = :attempt")
    int markFailed(@Param("id") String deliveryId, @Param("attempt") int attempt,
                   @Param("status") String status, @Param("nextAttemptAt") Instant nextAttemptAt,
                   @Param("error") String error, @Param("now") Instant now);

    // The locking CTE fixes the candidate set for the whole update. A direct
    // IN (SELECT ... LIMIT ... FOR UPDATE) can be re-evaluated by PostgreSQL
    // while updating and exceed the batch bound (covered by the container test).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_detection_route_outbox set "
            + "status = case when attempts >= :maxAttempts then 'DEAD' else 'PENDING' end, "
            + "next_attempt_at = :now, updated_at = :now, last_error = 'route publish lease expired' "
            + "where delivery_id in (with candidates as (select delivery_id from t_detection_route_outbox "
            + "where status = 'PROCESSING' and updated_at < :cutoff "
            + "order by updated_at, delivery_id limit :batchSize for update skip locked) "
            + "select delivery_id from candidates) "
            + "and status = 'PROCESSING' and updated_at < :cutoff", nativeQuery = true)
    int recoverStaleBatch(@Param("cutoff") Instant cutoff, @Param("now") Instant now,
                          @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_detection_route_outbox set status = 'DEAD', "
            + "updated_at = :now, last_error = 'route publish retry limit reached' "
            + "where delivery_id in (with candidates as (select delivery_id from t_detection_route_outbox "
            + "where status = 'PENDING' and attempts >= :maxAttempts "
            + "order by updated_at, delivery_id limit :batchSize for update skip locked) "
            + "select delivery_id from candidates) "
            + "and status = 'PENDING' and attempts >= :maxAttempts", nativeQuery = true)
    int markExhaustedBatch(@Param("maxAttempts") int maxAttempts, @Param("now") Instant now,
                           @Param("batchSize") int batchSize);
}
