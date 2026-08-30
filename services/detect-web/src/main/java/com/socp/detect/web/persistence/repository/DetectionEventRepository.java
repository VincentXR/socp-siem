package com.socp.detect.web.persistence.repository;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
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
    List<DetectionEventEntity> findByTenantId(String tenantId);
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

    long countByStatus(String status);

    long countByTenantIdAndStatus(String tenantId, String status);

    java.util.Optional<DetectionEventEntity> findByTenantIdAndSourceEventId(
            String tenantId, String sourceEventId);

    List<DetectionEventEntity> findByTenantIdAndStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
            String tenantId, String status, Instant after, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from DetectionEventEntity e "
            + "where e.status = :status and "
            + "(e.completedAt < :before or (e.completedAt is null and e.occurredAt < :before))")
    long deleteCompletedBefore(@Param("status") String status,
                               @Param("before") Instant before);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from DetectionEventEntity e "
            + "where e.status = :status and "
            + "(e.deadLetteredAt < :before or (e.deadLetteredAt is null and e.occurredAt < :before))")
    long deleteDeadLetteredBefore(@Param("status") String status,
                                  @Param("before") Instant before);
}
