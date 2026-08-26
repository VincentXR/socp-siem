package com.socp.alert.repository;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlarmDeliveryRepository extends JpaRepository<AlarmDelivery, String> {

    List<AlarmDelivery> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant nextAttemptAt);

    Optional<AlarmDelivery> findByIdAndTenantId(String id, String tenantId);

    List<AlarmDelivery> findByTenantIdAndAlarmIdOrderByDestinationAsc(String tenantId, String alarmId);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'PROCESSING', d.claimedAt = :now, "
            + "d.updatedAt = :now, d.attempts = d.attempts + 1 "
            + "where d.id = :id and d.status = 'PENDING' and d.nextAttemptAt <= :now "
            + "and d.attempts < :maxAttempts")
    int claim(@Param("id") String id, @Param("now") Instant now,
              @Param("maxAttempts") int maxAttempts);

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
    @Query("update AlarmDelivery d set d.status = 'DEAD', d.claimedAt = null, d.lastError = :error, "
            + "d.updatedAt = :now where d.id = :id and d.status = 'PROCESSING'")
    int markDead(@Param("id") String id, @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'PENDING', d.nextAttemptAt = :now, "
            + "d.claimedAt = null, d.updatedAt = :now "
            + "where d.status = 'PROCESSING' and d.claimedAt < :cutoff")
    int recoverStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'DEAD', d.claimedAt = null, "
            + "d.lastError = coalesce(d.lastError, :reason), d.updatedAt = :now "
            + "where d.status = 'PENDING' and d.attempts >= :maxAttempts")
    int markExhausted(@Param("maxAttempts") int maxAttempts, @Param("reason") String reason,
                      @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update AlarmDelivery d set d.status = 'PENDING', d.attempts = 0, d.nextAttemptAt = :now, "
            + "d.claimedAt = null, d.lastError = null, d.updatedAt = :now "
            + "where d.id = :id and d.tenantId = :tenantId and d.status = 'DEAD'")
    int requeueDead(@Param("id") String id, @Param("tenantId") String tenantId,
                    @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("delete from AlarmDelivery d where d.status = 'DELIVERED' and d.deliveredAt < :cutoff")
    int deleteDeliveredBefore(@Param("cutoff") Instant cutoff);
}
