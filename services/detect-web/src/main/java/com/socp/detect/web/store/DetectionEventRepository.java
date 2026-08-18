package com.socp.detect.web.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface DetectionEventRepository extends JpaRepository<DetectionEventEntity, String> {

    List<DetectionEventEntity> findTop10000ByOccurredAtAfterOrderByOccurredAtAsc(Instant after);

    @Query("select e from DetectionEventEntity e "
            + "where e.kafkaPartition in :partitions and e.occurredAt > :after "
            + "order by e.kafkaPartition asc, e.kafkaOffset asc, e.occurredAt asc")
    List<DetectionEventEntity> findByKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
            @Param("partitions") Set<Integer> partitions, @Param("after") Instant after,
            org.springframework.data.domain.Pageable pageable);

    long deleteByOccurredAtBefore(Instant before);
}
