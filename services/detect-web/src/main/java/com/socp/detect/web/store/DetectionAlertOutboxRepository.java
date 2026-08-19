package com.socp.detect.web.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Repository for the durable Detection -> Alert Web hand-off. */
public interface DetectionAlertOutboxRepository extends JpaRepository<DetectionAlertOutboxEntity, String> {

    List<DetectionAlertOutboxEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);

    List<DetectionAlertOutboxEntity> findByStatusAndUpdatedAtBefore(String status, Instant cutoff);

    @Modifying
    @Transactional
    @Query("update DetectionAlertOutboxEntity e set e.status = 'PROCESSING', e.updatedAt = :now " +
            "where e.alertId = :alertId and e.status = :expectedStatus")
    int claim(@Param("alertId") String alertId,
              @Param("expectedStatus") String expectedStatus,
              @Param("now") Instant now);
}
