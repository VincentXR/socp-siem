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
    // Preserve the composite identity lookup for tenants missing from statistics;
    // PostgreSQL evaluates this scalar tenant once as an InitPlan.
    @Query(value = "select e.* from t_ingestion_outbox e "
            + "where e.tenant_id = (select cast(:tenantId as varchar)) and e.event_id in (:eventIds)", nativeQuery = true)
    List<IngestionOutboxEvent> findByTenantIdAndEventIdIn(@Param("tenantId") String tenantId,
                                                       @Param("eventIds") Collection<String> eventIds);

    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    // Find eligible heads in index order before expanding their first 16
    // sequence positions. Rank only selected keys, then fetch at most 200 payloads.
    // The ID tie-break keeps legacy duplicate sequences within the same limits.
    // Scalar statuses avoid estimated-empty global scans while stats lag.
    // Retain blocker order with OFFSET 0 so EXISTS uses the covering key index
    // instead of repeatedly filtering retained payloads or choosing a heap scan.
    @Query(value = """
            with heads as (
                select head.id as head_id, head.tenant_id, head.routing_key,
                       head.next_attempt_at as head_due, head.sequence_no as head_sequence
                from t_ingestion_outbox head
                where head.status = 'PENDING' and head.next_attempt_at <= :now
                  and head.id = (
                      select first_pending.id from t_ingestion_outbox first_pending
                      where first_pending.tenant_id = head.tenant_id
                        and first_pending.routing_key = head.routing_key and first_pending.status = (select cast('PENDING' as varchar))
                      order by first_pending.sequence_no, first_pending.id fetch first 1 row only)
                  and not exists (
                      select 1 from t_ingestion_outbox blocker
                      where blocker.tenant_id = head.tenant_id and blocker.routing_key = head.routing_key
                        and blocker.status = (select cast('PROCESSING' as varchar))
                      order by blocker.sequence_no, blocker.id offset 0)
                order by head.next_attempt_at, head.sequence_no, head.id
                limit 200
            ), selected as (
                select pending.id, pending.sequence_no, pending.next_attempt_at,
                       heads.head_due, heads.head_sequence, heads.head_id,
                       row_number() over (partition by heads.tenant_id, heads.routing_key
                                          order by pending.sequence_no, pending.id) as key_position
                from heads join t_ingestion_outbox pending
                  on pending.tenant_id = heads.tenant_id and pending.routing_key = heads.routing_key
                 and pending.status = (select cast('PENDING' as varchar))
                 and pending.sequence_no <= coalesce((
                     select cutoff.sequence_no from t_ingestion_outbox cutoff
                     where cutoff.tenant_id = heads.tenant_id and cutoff.routing_key = heads.routing_key
                       and cutoff.status = (select cast('PENDING' as varchar))
                     order by cutoff.sequence_no, cutoff.id offset 15 rows fetch first 1 row only
                 ), 9223372036854775807)
            ), due as (
                select id, head_due, head_sequence, head_id, sequence_no from selected
                where key_position <= 16 and next_attempt_at <= :now
                order by head_due, head_sequence, sequence_no, head_id, id limit 200
            )
            select queued.* from due join t_ingestion_outbox queued on queued.id = due.id
            order by due.head_due, due.head_sequence, due.sequence_no, due.head_id, due.id
            """, nativeQuery = true)
    List<IngestionOutboxEvent> findDueOrderedBatch(@Param("now") Instant now);

    long countByStatus(String status);

    List<IngestionOutboxEvent> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(
            String tenantId, String status);

    @Query("select min(e.createdAt) from IngestionOutboxEvent e where e.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(e.updatedAt) from IngestionOutboxEvent e where e.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

    // Conditional status fences keep stale estimates from turning one ID/key
    // lookup into a scan of the complete pending queue.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PROCESSING', e.claimToken = :token, "
            + "e.processingKey = concat(concat(e.tenantId, ':'), e.routingKey), "
            + "e.updatedAt = :now, e.claimedAt = :now, "
            + "e.attempts = e.attempts + 1 where e.id = :id "
            + "and case when e.status = 'PENDING' then e.attempts else null end = :expectedAttempts "
            + "and e.nextAttemptAt <= :now and e.attempts < :maxAttempts "
            + "and not exists (select blocker.id from IngestionOutboxEvent blocker "
            + "where blocker.tenantId = e.tenantId and blocker.routingKey = e.routingKey "
            + "and case when blocker.status = 'PROCESSING' then 1 else 0 end = 1) "
            + "and not exists (select blocker.id from IngestionOutboxEvent blocker "
            + "where blocker.tenantId = e.tenantId and blocker.routingKey = e.routingKey "
            + "and case when blocker.status = 'PENDING' then 1 else 0 end = 1 "
            + "and blocker.sequenceNo < e.sequenceNo)")
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("expectedAttempts") int expectedAttempts, @Param("token") String token);

    // Keep the status/token fence as one conditional predicate. PostgreSQL can
    // otherwise prefer an estimated-empty status index over the unique ID,
    // scanning every active claim for each acknowledgement while stats lag.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PUBLISHED', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.publishedAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.id = :id and case when e.status = 'PROCESSING' then e.claimToken else null end = :token")
    int markPublished(@Param("id") String id, @Param("now") Instant now, @Param("token") String token);

    // A newly active tenant can also be estimated empty: retain its fence inside
    // the conditional so the bounded identity set remains the index condition.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PUBLISHED', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.publishedAt = :now, e.lastError = null, e.updatedAt = :now "
            + "where e.id in :ids "
            + "and case when e.tenantId = :tenantId and e.status = 'PROCESSING' "
            + "then e.claimToken else null end = :token")
    int markPublishedBatch(@Param("tenantId") String tenantId, @Param("ids") Collection<String> ids,
                           @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PENDING', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.nextAttemptAt = :nextAttemptAt, e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and case when e.status = 'PROCESSING' then e.claimToken else null end = :token")
    int scheduleRetry(@Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    /** Return an unstarted publication without spending its reserved attempt. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'PENDING', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.attempts = case when e.attempts > 0 then e.attempts - 1 else 0 end, "
            + "e.nextAttemptAt = :now, e.updatedAt = :now "
            + "where e.id = :id and e.tenantId = :tenantId "
            + "and case when e.status = 'PROCESSING' then e.claimToken else null end = :token")
    int releaseUnsentClaim(@Param("id") String id, @Param("tenantId") String tenantId,
                           @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update IngestionOutboxEvent e set e.status = 'DEAD', e.claimToken = null, "
            + "e.processingKey = null, e.claimedAt = null, "
            + "e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and case when e.status = 'PROCESSING' then e.claimToken else null end = :token")
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
