package com.socp.search.config.persistence.repository;

import com.socp.search.config.domain.IngestionOutboxEvent;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IngestionOutboxRepository extends TenantScopedRepository<IngestionOutboxEvent, String> {
    List<IngestionOutboxEvent> findByTenantId(String tenantId);
    Optional<IngestionOutboxEvent> findByIdAndTenantId(String id, String tenantId);
    List<IngestionOutboxEvent> findByTenantIdAndEventIdIn(String tenantId, Collection<String> eventIds);

    @Query(value = "select current.* from t_ingestion_outbox current "
            + "where current.status = 'PENDING' and current.next_attempt_at <= :now "
            + "and not exists (select 1 from t_ingestion_outbox blocker "
            + "where blocker.tenant_id = current.tenant_id and blocker.routing_key = current.routing_key "
            + "and (blocker.status = 'PROCESSING' or "
            + "(blocker.status = 'PENDING' and blocker.sequence_no < current.sequence_no))) "
            + "order by current.next_attempt_at, current.sequence_no limit 200", nativeQuery = true)
    List<IngestionOutboxEvent> findDueKeyHeads(@Param("now") Instant now);

    long countByStatus(String status);

    List<IngestionOutboxEvent> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(
            String tenantId, String status);

    @Query("select min(e.createdAt) from IngestionOutboxEvent e where e.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(e.updatedAt) from IngestionOutboxEvent e where e.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PROCESSING', e.claimToken = :token, "
            + "e.processingKey = concat(concat(e.tenantId, ':'), e.routingKey), "
            + "e.updatedAt = :now, e.claimedAt = :now, "
            + "e.attempts = e.attempts + 1 where e.id = :id and e.status = 'PENDING' "
            + "and e.nextAttemptAt <= :now and e.attempts < :maxAttempts and e.attempts = :expectedAttempts "
            + "and not exists (select blocker.id from IngestionOutboxEvent blocker "
            + "where blocker.tenantId = e.tenantId and blocker.routingKey = e.routingKey "
            + "and (blocker.status = 'PROCESSING' or "
            + "(blocker.status = 'PENDING' and blocker.sequenceNo < e.sequenceNo)))")
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("expectedAttempts") int expectedAttempts, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PUBLISHED', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.publishedAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int markPublished(@Param("id") String id, @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PENDING', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.nextAttemptAt = :nextAttemptAt, e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int scheduleRetry(@Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'DEAD', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING' and e.claimToken = :token")
    int markDead(@Param("id") String id, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    // The locking CTE fixes a bounded candidate set and skips competing owners.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox set status = 'PENDING', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "next_attempt_at = :now, updated_at = :now where id in ( "
            + "with candidates as (select id from t_ingestion_outbox where status = 'PROCESSING' and claimed_at < :cutoff "
            + "order by claimed_at, id limit :batchSize for update skip locked) select id from candidates) "
            + "and status = 'PROCESSING' and claimed_at < :cutoff", nativeQuery = true)
    int recoverStaleBatch(@Param("cutoff") Instant cutoff, @Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox set status = 'DEAD', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "last_error = coalesce(last_error, :reason), updated_at = :now where id in ( "
            + "with candidates as (select id from t_ingestion_outbox where status = 'PENDING' and attempts >= :maxAttempts "
            + "order by updated_at, id limit :batchSize for update skip locked) select id from candidates) "
            + "and status = 'PENDING' and attempts >= :maxAttempts", nativeQuery = true)
    int markExhaustedBatch(@Param("maxAttempts") int maxAttempts, @Param("reason") String reason, @Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PENDING', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.attempts = 0, e.nextAttemptAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.id = :id and e.tenantId = :tenantId and e.status = 'DEAD'")
    int requeueDead(@Param("id") String id, @Param("tenantId") String tenantId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'DISCARDED', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.lastError = :reason, e.updatedAt = :now "
            + "where e.id = :id and e.tenantId = :tenantId and e.status = 'DEAD'")
    int discardDead(@Param("id") String id, @Param("tenantId") String tenantId, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = "delete from t_ingestion_outbox where id in ( "
            + "select id from t_ingestion_outbox where status = 'PUBLISHED' and published_at < :cutoff "
            + "order by published_at asc limit :batchSize)", nativeQuery = true)
    int deletePublishedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = "delete from t_ingestion_outbox where id in ( "
            + "select id from t_ingestion_outbox where status = 'DISCARDED' and updated_at < :cutoff "
            + "order by updated_at asc limit :batchSize)", nativeQuery = true)
    int deleteDiscardedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);
}
