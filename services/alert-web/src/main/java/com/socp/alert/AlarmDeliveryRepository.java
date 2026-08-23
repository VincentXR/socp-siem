package com.socp.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface AlarmDeliveryRepository extends JpaRepository<AlarmDelivery, String> {

    List<AlarmDelivery> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant nextAttemptAt);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'PROCESSING', d.claimedAt = :now, "
            + "d.updatedAt = :now, d.attempts = d.attempts + 1 "
            + "where d.id = :id and d.status = 'PENDING' and d.nextAttemptAt <= :now")
    int claim(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'DELIVERED', d.deliveredAt = :now, "
            + "d.claimedAt = null, d.lastError = null, d.updatedAt = :now "
            + "where d.id = :id and d.status = 'PROCESSING'")
    int markDelivered(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'PENDING', d.nextAttemptAt = :nextAttemptAt, "
            + "d.claimedAt = null, d.lastError = :error, d.updatedAt = :now "
            + "where d.id = :id and d.status = 'PROCESSING'")
    int scheduleRetry(@Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'PENDING', d.nextAttemptAt = :now, "
            + "d.claimedAt = null, d.updatedAt = :now "
            + "where d.status = 'PROCESSING' and d.claimedAt < :cutoff")
    int recoverStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
