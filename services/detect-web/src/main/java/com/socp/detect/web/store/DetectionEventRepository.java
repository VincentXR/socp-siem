package com.socp.detect.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface DetectionEventRepository extends JpaRepository<DetectionEventEntity, String> {

    List<DetectionEventEntity> findTop10000ByOccurredAtAfterOrderByOccurredAtAsc(Instant after);

    long deleteByOccurredAtBefore(Instant before);
}
