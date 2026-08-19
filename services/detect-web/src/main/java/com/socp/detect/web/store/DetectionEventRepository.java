package com.socp.detect.web.store;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface DetectionEventRepository extends JpaRepository<DetectionEventEntity, String> {

    List<DetectionEventEntity> findByStatusAndOccurredAtAfterOrderByOccurredAtAscEventIdAsc(
            String status, Instant after, Pageable pageable);

    @Query("select e from DetectionEventEntity e "
            + "where e.status = :status and e.kafkaPartition in :partitions "
            + "and e.occurredAt > :after "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.occurredAt asc, e.eventId asc")
    List<DetectionEventEntity> findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
            @Param("status") String status,
            @Param("partitions") Set<Integer> partitions,
            @Param("after") Instant after,
            Pageable pageable);

    long countByStatus(String status);

    long deleteByOccurredAtBefore(Instant before);
}
