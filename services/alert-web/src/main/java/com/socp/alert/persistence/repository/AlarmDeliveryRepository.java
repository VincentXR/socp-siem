package com.socp.alert.persistence.repository;

import com.socp.alert.domain.AlarmDelivery;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlarmDeliveryRepository extends TenantScopedRepository<AlarmDelivery, String> {
    List<AlarmDelivery> findByTenantId(String tenantId);

    // Select independent deliveries by eligibility time, so delayed old retries
    // cannot force a scan before newly due work. Load only the bounded payloads.
    @Query(value = "select delivery.* from (select id, next_attempt_at, created_at from alarm_delivery "
            + "where status=:status and next_attempt_at<=:nextAttemptAt order by next_attempt_at,created_at,id limit 100) due "
            + "join alarm_delivery delivery on delivery.id=due.id order by due.next_attempt_at,due.created_at,due.id", nativeQuery = true)
    List<AlarmDelivery> findDueBatch(
            @Param("status") String status, @Param("nextAttemptAt") Instant nextAttemptAt);

    List<AlarmDelivery> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(String tenantId, String status);

    // ID is NOT NULL; count(*) permits a status-index-only count.
    @Query("select count(*) from AlarmDelivery d where d.status = :status")
    long countByStatus(@Param("status") String status);

    @Query("select min(d.createdAt) from AlarmDelivery d where d.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(d.updatedAt) from AlarmDelivery d where d.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

    Optional<AlarmDelivery> findByIdAndTenantId(String id, String tenantId);

    List<AlarmDelivery> findByTenantIdAndIdIn(String tenantId, Iterable<String> ids);

    List<AlarmDelivery> findByTenantIdAndAlarmIdOrderByDestinationAsc(String tenantId, String alarmId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'PROCESSING', e.claimToken = :token, e.updatedAt = :now, e.claimedAt = :now, "
            + "e.attempts = e.attempts + 1 where e.id = :id and e.status = 'PENDING' "
            + "and e.nextAttemptAt <= :now and e.attempts < :maxAttempts and e.attempts = :expectedAttempts")
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("expectedAttempts") int expectedAttempts, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'DELIVERED', e.claimToken = null, e.claimedAt = null, "
            + "e.deliveredAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int markDelivered(@Param("id") String id, @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'PENDING', e.claimToken = null, e.claimedAt = null, "
            + "e.nextAttemptAt = :nextAttemptAt, e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int scheduleRetry(@Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'DEAD', e.claimToken = null, e.claimedAt = null, "
            + "e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int markDead(@Param("id") String id, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'PROCESSING', e.claimToken = :token, e.updatedAt = :now, e.claimedAt = :now, "
            + "e.attempts = e.attempts + 1 where e.tenantId = :tenantId and e.id = :id and e.status = 'PENDING' "
            + "and e.nextAttemptAt <= :now and e.attempts < :maxAttempts and e.attempts = :expectedAttempts")
    int claimForTenant(@Param("tenantId") String tenantId, @Param("id") String id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("expectedAttempts") int expectedAttempts, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'DELIVERED', e.claimToken = null, e.claimedAt = null, "
            + "e.deliveredAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.tenantId = :tenantId and e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int markDeliveredForTenant(@Param("tenantId") String tenantId, @Param("id") String id, @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'PENDING', e.claimToken = null, e.claimedAt = null, "
            + "e.nextAttemptAt = :nextAttemptAt, e.lastError = :error, e.updatedAt = :now "
            + "where e.tenantId = :tenantId and e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int scheduleRetryForTenant(@Param("tenantId") String tenantId, @Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'DEAD', e.claimToken = null, e.claimedAt = null, "
            + "e.lastError = :error, e.updatedAt = :now "
            + "where e.tenantId = :tenantId and e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int markDeadForTenant(@Param("tenantId") String tenantId, @Param("id") String id, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    // The locking CTE fixes a bounded candidate set and skips competing owners.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update alarm_delivery set status = 'PENDING', claim_token = null, claimed_at = null, "
            + "next_attempt_at = :now, updated_at = :now where id in ( "
            + "with candidates as (select id from alarm_delivery where status = 'PROCESSING' and claimed_at < :cutoff "
            + "order by claimed_at, id limit :batchSize for update skip locked) select id from candidates) "
            + "and status = 'PROCESSING' and claimed_at < :cutoff", nativeQuery = true)
    int recoverStaleBatch(@Param("cutoff") Instant cutoff, @Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    // Filter eligible attempts before ordering: a generic plan must not scan
    // every PENDING row through the age index. Lock only the bounded outer rows.
    @Query(value = "update alarm_delivery set status = 'DEAD', claim_token = null, claimed_at = null, "
            + "last_error = coalesce(last_error, :reason), updated_at = :now where id in ( "
            + "with candidates as (select queued.id from (select id, updated_at from alarm_delivery "
            + "where status = 'PENDING' and attempts >= :maxAttempts offset 0) eligible "
            + "join alarm_delivery queued on queued.id=eligible.id "
            + "order by eligible.updated_at, eligible.id limit :batchSize for update of queued skip locked) "
            + "select id from candidates) "
            + "and status = 'PENDING' and attempts >= :maxAttempts", nativeQuery = true)
    int markExhaustedBatch(@Param("maxAttempts") int maxAttempts, @Param("reason") String reason, @Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'PENDING', e.claimToken = null, e.claimedAt = null, "
            + "e.attempts = 0, e.nextAttemptAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.id = :id and e.tenantId = :tenantId and e.status = 'DEAD'")
    int requeueDead(@Param("id") String id, @Param("tenantId") String tenantId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update AlarmDelivery e set e.status = 'DISCARDED', e.claimToken = null, e.claimedAt = null, "
            + "e.lastError = :reason, e.updatedAt = :now "
            + "where e.id = :id and e.tenantId = :tenantId and e.status = 'DEAD'")
    int discardDead(@Param("id") String id, @Param("tenantId") String tenantId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = "delete from alarm_delivery where id in ( "
            + "select id from alarm_delivery where status = 'DELIVERED' and delivered_at < :cutoff "
            + "order by delivered_at asc limit :batchSize)", nativeQuery = true)
    int deleteDeliveredBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = "delete from alarm_delivery where id in ( "
            + "select id from alarm_delivery where status = 'DISCARDED' and updated_at < :cutoff "
            + "order by updated_at asc limit :batchSize)", nativeQuery = true)
    int deleteDiscardedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);
}
