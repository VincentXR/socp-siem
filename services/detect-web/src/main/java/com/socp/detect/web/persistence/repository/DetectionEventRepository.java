package com.socp.detect.web.persistence.repository;


import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import org.springframework.data.domain.Pageable;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface DetectionEventRepository extends TenantScopedRepository<DetectionEventEntity, String> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update DetectionEventEntity e set e.completedAt = case when e.status = 'PENDING' then :completedAt else e.completedAt end, "
            + "e.statusReason = case when e.status = 'PENDING' then null else e.statusReason end, "
            + "e.resultJson = case when e.status = 'PENDING' then :result else e.resultJson end, "
            + "e.status = case when e.status = 'PENDING' then 'COMPLETED' else e.status end "
            + "where e.tenantId = :tenant and e.deliveryId = :deliveryId")
    int completeResult(@Param("tenant") String tenant, @Param("deliveryId") String deliveryId,
                       @Param("result") String result, @Param("completedAt") Instant completedAt);

    List<DetectionEventEntity> findByTenantId(String tenantId);
    // Keep both identity bounds in the index when a new tenant is absent from
    // PostgreSQL statistics; a tenant-only scan grows with its retained journal.
    @Query(value = "select * from t_detection_event where tenant_id = (select cast(:tenantId as varchar)) "
            + "and delivery_id in (:deliveryIds)", nativeQuery = true)
    List<DetectionEventEntity> findByTenantIdAndDeliveryIdIn(
            @Param("tenantId") String tenantId, @Param("deliveryIds") java.util.Collection<String> deliveryIds);
    java.util.Optional<DetectionEventEntity> findByStorageIdAndTenantId(String storageId, String tenantId);

    List<DetectionEventEntity> findByStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
            String status, Instant after, Pageable pageable);

    @Query("select e from DetectionEventEntity e "
            + "where e.status = :status and e.kafkaPartition in :partitions "
            + "and e.occurredAt > :after "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.occurredAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
            @Param("status") String status,
            @Param("partitions") Set<Integer> partitions,
            @Param("after") Instant after,
            Pageable pageable);

    @Query("select e from DetectionEventEntity e "
            + "where e.tenantId = :tenant and e.status = :status "
            + "and e.kafkaPartition in :partitions and e.occurredAt > :after "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.occurredAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByTenantIdAndStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
            @Param("tenant") String tenant,
            @Param("status") String status,
            @Param("partitions") Set<Integer> partitions,
            @Param("after") Instant after,
            Pageable pageable);

    long countByStatus(String status);

    long countByTenantIdAndStatus(String tenantId, String status);

    java.util.Optional<DetectionEventEntity> findByTenantIdAndSourceEventId(
            String tenantId, String sourceEventId);

    List<DetectionEventEntity> findByTenantIdAndStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
            String tenantId, String status, Instant after, Pageable pageable);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.completedAt > :after order by e.completedAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByTenantIdAndStatusAndCompletedAtAfterOrderByCompletedAt(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("after") Instant after, Pageable pageable);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.kafkaPartition in :partitions and e.completedAt > :after "
            + "order by e.completedAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByTenantIdAndStatusAndKafkaPartitionInAndCompletedAtAfterOrderByCompletedAt(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("partitions") Set<Integer> partitions, @Param("after") Instant after,
            Pageable pageable);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.kafkaPartition is not null and e.kafkaOffset is not null "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.completedAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByTenantIdAndStatusAndOrderByKafkaPosition(
            @Param("tenant") String tenantId, @Param("status") String status,
            Pageable pageable);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.kafkaPartition in :partitions and e.kafkaOffset is not null "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.completedAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByTenantIdAndStatusAndKafkaPartitionInOrderByKafkaPosition(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("partitions") Set<Integer> partitions, Pageable pageable);

    /** Legacy timestamp-bounded signatures retained for source compatibility. */
    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.completedAt > :after and e.kafkaPartition is not null "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.completedAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByTenantIdAndStatusAndCompletedAtAfterOrderByKafkaPosition(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("after") Instant after, Pageable pageable);

    /** Legacy timestamp-bounded signatures retained for source compatibility. */
    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.kafkaPartition in :partitions and e.completedAt > :after "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.completedAt asc, e.sourceEventId asc")
    List<DetectionEventEntity> findByTenantIdAndStatusAndKafkaPartitionInAndCompletedAtAfterOrderByKafkaPosition(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("partitions") Set<Integer> partitions, @Param("after") Instant after,
            Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "delete from t_detection_event where event_id in ("
            + "select event_id from t_detection_event where status = :status and "
            + "(completed_at < :before or (completed_at is null and occurred_at < :before)) "
            + "order by coalesce(completed_at, occurred_at) asc limit :batchSize)", nativeQuery = true)
    int deleteCompletedBatchBefore(@Param("status") String status,
                                   @Param("before") Instant before,
                                   @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "delete from t_detection_event where event_id in ("
            + "select event_id from t_detection_event where status = :status and "
            + "(dead_lettered_at < :before or (dead_lettered_at is null and occurred_at < :before)) "
            + "order by coalesce(dead_lettered_at, occurred_at) asc limit :batchSize)", nativeQuery = true)
    int deleteDeadLetteredBatchBefore(@Param("status") String status,
                                      @Param("before") Instant before,
                                      @Param("batchSize") int batchSize);
    java.util.Optional<DetectionEventEntity> findByTenantIdAndDeliveryId(
            String tenantId, String deliveryId);

    List<DetectionEventEntity> findByTenantIdAndSourceEventIdOrderByOccurredAtAsc(
            String tenantId, String sourceEventId);

    List<DetectionEventEntity> findByStatusAndDeliveryTopicAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
            String status, String deliveryTopic, Instant after, Pageable pageable);

    List<DetectionEventEntity> findByTenantIdAndStatusAndDeliveryTopicAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
            String tenantId, String status, String deliveryTopic, Instant after, Pageable pageable);

    @Query("select e from DetectionEventEntity e "
            + "where e.status = :status and e.deliveryTopic = :topic "
            + "and e.kafkaPartition in :partitions and e.occurredAt > :after "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.occurredAt asc, e.deliveryId asc")
    List<DetectionEventEntity> findByStatusAndTopicAndKafkaPartitionInAfter(
            @Param("status") String status, @Param("topic") String deliveryTopic,
            @Param("partitions") Set<Integer> partitions, @Param("after") Instant after,
            Pageable pageable);

    @Query("select e from DetectionEventEntity e "
            + "where e.tenantId = :tenant and e.status = :status and e.deliveryTopic = :topic "
            + "and e.kafkaPartition in :partitions and e.occurredAt > :after "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.occurredAt asc, e.deliveryId asc")
    List<DetectionEventEntity> findByTenantStatusTopicAndKafkaPartitionInAfter(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("topic") String deliveryTopic, @Param("partitions") Set<Integer> partitions,
            @Param("after") Instant after, Pageable pageable);

    long countByStatusAndDeliveryTopic(String status, String deliveryTopic);

    long countByTenantIdAndStatusAndDeliveryTopic(String tenantId, String status, String deliveryTopic);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.deliveryTopic = :topic and e.completedAt > :after "
            + "order by e.completedAt asc, e.deliveryId asc")
    List<DetectionEventEntity> findByTenantStatusTopicCompletedAfter(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("topic") String deliveryTopic, @Param("after") Instant after, Pageable pageable);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.deliveryTopic = :topic and e.kafkaPartition in :partitions "
            + "and e.completedAt > :after order by e.completedAt asc, e.deliveryId asc")
    List<DetectionEventEntity> findByTenantStatusTopicPartitionsCompletedAfter(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("topic") String deliveryTopic, @Param("partitions") Set<Integer> partitions,
            @Param("after") Instant after, Pageable pageable);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.deliveryTopic = :topic and e.kafkaPartition is not null and e.kafkaOffset is not null "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.completedAt asc, e.deliveryId asc")
    List<DetectionEventEntity> findByTenantStatusTopicOrderByKafkaPosition(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("topic") String deliveryTopic, Pageable pageable);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.deliveryTopic = :topic and e.kafkaPartition in :partitions and e.kafkaOffset is not null "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.completedAt asc, e.deliveryId asc")
    List<DetectionEventEntity> findByTenantStatusTopicPartitionsOrderByKafkaPosition(
            @Param("tenant") String tenantId, @Param("status") String status,
            @Param("topic") String deliveryTopic, @Param("partitions") Set<Integer> partitions,
            Pageable pageable);

    @Query("select distinct e.tenantId from DetectionEventEntity e where e.status = :status "
            + "and e.deliveryTopic = :topic and e.occurredAt > :after "
            + "and (:tenant is null or e.tenantId = :tenant) order by e.tenantId")
    List<String> recoveryTenants(@Param("status") String status, @Param("topic") String topic,
            @Param("after") Instant after, @Param("tenant") String tenant, Pageable pageable);

    @Query("select distinct e.tenantId from DetectionEventEntity e where e.status = :status "
            + "and e.deliveryTopic = :topic and e.occurredAt > :after and e.kafkaPartition in :partitions "
            + "and (:tenant is null or e.tenantId = :tenant) order by e.tenantId")
    List<String> recoveryTenantsForPartitions(@Param("status") String status, @Param("topic") String topic,
            @Param("after") Instant after, @Param("tenant") String tenant,
            @Param("partitions") Set<Integer> partitions, Pageable pageable);

    @Query("select distinct e.kafkaPartition from DetectionEventEntity e where e.tenantId = :tenant "
            + "and e.status = :status and e.deliveryTopic = :topic and e.kafkaPartition is not null")
    List<Integer> completedPartitions(@Param("tenant") String tenant, @Param("status") String status,
            @Param("topic") String topic);

    @Query("select e from DetectionEventEntity e where e.tenantId = :tenant and e.status = :status "
            + "and e.deliveryTopic = :topic and e.kafkaPartition = :partition and e.kafkaOffset > :offset "
            + "order by e.kafkaOffset, e.completedAt, e.deliveryId")
    List<DetectionEventEntity> partitionTail(@Param("tenant") String tenant, @Param("status") String status,
            @Param("topic") String topic, @Param("partition") int partition, @Param("offset") long offset,
            Pageable pageable);

}
