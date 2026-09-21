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

    List<DetectionRouteOutboxEntity> findTop100ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(String status, Instant cutoff);

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

    @Modifying
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = 'PROCESSING', "
            + "e.attempts = e.attempts + 1, e.updatedAt = :now "
            + "where e.deliveryId = :id and e.status = 'PENDING' "
            + "and e.nextAttemptAt <= :now and e.attempts < :maxAttempts and e.attempts = :expectedAttempt")
    int claim(@Param("id") String deliveryId, @Param("now") Instant now,
              @Param("maxAttempts") int maxAttempts, @Param("expectedAttempt") int expectedAttempt);

    @Modifying
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = 'PUBLISHED', "
            + "e.deliveryPartition = :partition, e.deliveryOffset = :offset, "
            + "e.publishedAt = :now, e.updatedAt = :now, e.lastError = null "
            + "where e.deliveryId = :id and e.status = 'PROCESSING' and e.attempts = :attempt")
    int completeAttempt(@Param("id") String id, @Param("attempt") int attempt,
                        @Param("partition") int partition, @Param("offset") long offset,
                        @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = :status, e.nextAttemptAt = :next, "
            + "e.updatedAt = :now, e.lastError = :error "
            + "where e.deliveryId = :id and e.status = 'PROCESSING' and e.attempts = :attempt")
    int failAttempt(@Param("id") String id, @Param("attempt") int attempt,
                    @Param("status") String status, @Param("next") Instant next,
                    @Param("now") Instant now, @Param("error") String error);

    @Modifying
    @Transactional
    @Query("update DetectionRouteOutboxEntity e set e.status = :status, e.nextAttemptAt = :now, "
            + "e.updatedAt = :now, e.lastError = 'publication lease expired' "
            + "where e.deliveryId = :id and e.status = 'PROCESSING' and e.attempts = :attempt "
            + "and e.updatedAt < :cutoff")
    int recoverAttempt(@Param("id") String id, @Param("attempt") int attempt,
                       @Param("status") String status, @Param("now") Instant now,
                       @Param("cutoff") Instant cutoff);
}
