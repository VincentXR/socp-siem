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
    @Query(value = "select e.*, payload.stored_payload from t_ingestion_outbox e "
            + "left join t_ingestion_outbox_payload payload on payload.id=e.id "
            + "where e.tenant_id = (select cast(:tenantId as varchar)) and e.event_id in (:eventIds)", nativeQuery = true)
    List<IngestionOutboxEvent> findByTenantIdAndEventIdIn(@Param("tenantId") String tenantId,
                                                       @Param("eventIds") Collection<String> eventIds);

    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    // Deduplicate pending keys in the covering index before expanding their first 16
    // sequence positions. Rank only selected keys, then fetch at most 200 payloads.
    // The ID tie-break keeps legacy duplicate sequences within the same limits.
    // Scalar statuses avoid estimated-empty global scans while stats lag.
    // Match V21's active-index predicate without using stale status-frequency
    // estimates to collapse key membership/ownership probes into global scans.
    // Retain blocker order with OFFSET 0 so EXISTS uses the covering key index
    // instead of repeatedly filtering retained payloads or choosing a heap scan.
    @Query(value = """
            with first_pending as (
                select distinct on (tenant_id, routing_key)
                       id, tenant_id, routing_key, next_attempt_at, sequence_no
                from t_ingestion_outbox
                where coalesce(status,'') <> 'PUBLISHED' and status = 'PENDING'
                order by tenant_id, routing_key, sequence_no, id
            ), heads as (
                select head.id as head_id, head.tenant_id, head.routing_key,
                       head.next_attempt_at as head_due, head.sequence_no as head_sequence
                from first_pending head
                where head.next_attempt_at <= :now
                  and not exists (
                      select 1 from t_ingestion_outbox blocker
                      where blocker.tenant_id = head.tenant_id and blocker.routing_key = head.routing_key
                        and coalesce(blocker.status,'') <> 'PUBLISHED' and blocker.status = (select cast('PROCESSING' as varchar))
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
                 and coalesce(pending.status,'') <> 'PUBLISHED' and pending.status = (select cast('PENDING' as varchar))
                 and pending.sequence_no <= coalesce((
                     select cutoff.sequence_no from t_ingestion_outbox cutoff
                     where cutoff.tenant_id = heads.tenant_id and cutoff.routing_key = heads.routing_key
                       and coalesce(cutoff.status,'') <> 'PUBLISHED' and cutoff.status = (select cast('PENDING' as varchar))
                     order by cutoff.sequence_no, cutoff.id offset 15 rows fetch first 1 row only
                 ), 9223372036854775807)
            ), due as (
                select id, head_due, head_sequence, head_id, sequence_no, key_position from selected
                where key_position <= 16 and next_attempt_at <= :now
                order by key_position, head_due, head_sequence, sequence_no, head_id, id limit 200
            )
            select queued.*, payload.stored_payload from due join t_ingestion_outbox queued on queued.id = due.id
            left join t_ingestion_outbox_payload payload on payload.id=queued.id
            order by due.key_position, due.head_due, due.head_sequence, due.sequence_no, due.head_id, due.id
            """, nativeQuery = true)
    List<IngestionOutboxEvent> findDueOrderedFallback(@Param("now") Instant now);

    /** A full page of independent heads precedes every same-key successor. */
    default List<IngestionOutboxEvent> findDueOrderedBatch(Instant now) {
        var heads = findDueIndependentHeads(now);
        return heads.size() == 200 ? heads : findDueOrderedFallback(now);
    }

    @org.springframework.data.jpa.repository.QueryHints(
            @jakarta.persistence.QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    // Bound the due-order scan before checking predecessor and processing fences.
    // A full 200-head page is exactly the first fair page of the fallback query.
    // Keep fallback planning separate so its backlog-dependent cost cannot
    // trigger JIT compilation on the bounded path.
    @Query(value = """
            with candidate_window as (
                select candidate.id, candidate.tenant_id, candidate.routing_key,
                       candidate.next_attempt_at, candidate.sequence_no
                from t_ingestion_outbox candidate
                where coalesce(candidate.status,'') <> 'PUBLISHED' and candidate.status = (select cast('PENDING' as varchar))
                  and candidate.next_attempt_at <= :now
                order by candidate.next_attempt_at, candidate.sequence_no, candidate.id
                limit 400
            ), heads as (
                select candidate.id, candidate.next_attempt_at, candidate.sequence_no
                from candidate_window candidate
                where candidate.id = (
                    select first_row.id from t_ingestion_outbox first_row
                    where first_row.tenant_id = candidate.tenant_id
                      and first_row.routing_key = candidate.routing_key
                      and coalesce(first_row.status,'') <> 'PUBLISHED' and first_row.status = (select cast('PENDING' as varchar))
                    order by first_row.sequence_no, first_row.id limit 1)
                  and not exists (
                    select 1 from t_ingestion_outbox blocker
                    where blocker.tenant_id = candidate.tenant_id and blocker.routing_key = candidate.routing_key
                      and coalesce(blocker.status,'') <> 'PUBLISHED' and blocker.status = (select cast('PROCESSING' as varchar))
                    order by blocker.sequence_no, blocker.id offset 0)
                order by candidate.next_attempt_at, candidate.sequence_no, candidate.id
                limit 200
            )
            select queued.*, payload.stored_payload from heads join t_ingestion_outbox queued on queued.id = heads.id
            left join t_ingestion_outbox_payload payload on payload.id=queued.id
            order by heads.next_attempt_at, heads.sequence_no, heads.id
            """, nativeQuery = true)
    List<IngestionOutboxEvent> findDueIndependentHeads(@Param("now") Instant now);

    @Query("select count(*) from IngestionOutboxEvent e where e.status = :status")
    long countByStatus(@Param("status") String status);

    List<IngestionOutboxEvent> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(
            String tenantId, String status);

    @Query("select min(e.createdAt) from IngestionOutboxEvent e where e.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(e.updatedAt) from IngestionOutboxEvent e where e.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

    // Native single-statement updates retain the CAS predicate under row locks.
    // Multi-table entity HQL mutations can preselect IDs before taking that lock.
    // Conditional status fences keep stale estimates from turning one ID/key
    // lookup into a scan of the complete pending queue.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox e set status = 'PROCESSING', claim_token = :token, "
            + "processing_key = concat(concat(e.tenant_id, ':'), e.routing_key), "
            + "updated_at = :now, claimed_at = :now, "
            + "attempts = e.attempts + 1 where e.id = :id "
            + "and case when e.status = 'PENDING' then e.attempts else null end = :expectedAttempts "
            + "and e.next_attempt_at <= :now and e.attempts < :maxAttempts "
            + "and not exists (select blocker.id from t_ingestion_outbox blocker "
            + "where blocker.tenant_id = e.tenant_id and blocker.routing_key = e.routing_key "
            + "and coalesce(blocker.status,'') <> 'PUBLISHED' and case when blocker.status = 'PROCESSING' then 1 else 0 end = 1) "
            + "and not exists (select blocker.id from t_ingestion_outbox blocker "
            + "where blocker.tenant_id = e.tenant_id and blocker.routing_key = e.routing_key "
            + "and coalesce(blocker.status,'') <> 'PUBLISHED' and case when blocker.status = 'PENDING' then 1 else 0 end = 1 "
            + "and blocker.sequence_no < e.sequence_no)", nativeQuery = true)
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("expectedAttempts") int expectedAttempts, @Param("token") String token);

    // Keep the status/token fence as one conditional predicate. PostgreSQL can
    // otherwise prefer an estimated-empty status index over the unique ID,
    // scanning every active claim for each acknowledgement while stats lag.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox e set status = 'PUBLISHED', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "published_at = :now, last_error = null, updated_at = :now "
            + "where e.id = :id and case when e.status = 'PROCESSING' then e.claim_token else null end = :token", nativeQuery = true)
    int markPublished(@Param("id") String id, @Param("now") Instant now, @Param("token") String token);

    // A newly active tenant can also be estimated empty: retain its fence inside
    // the conditional so the bounded identity set remains the index condition.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox e set status = 'PUBLISHED', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "published_at = :now, last_error = null, updated_at = :now "
            + "where e.id in (:ids) "
            + "and case when e.tenant_id = :tenantId and e.status = 'PROCESSING' "
            + "then e.claim_token else null end = :token", nativeQuery = true)
    int markPublishedBatch(@Param("tenantId") String tenantId, @Param("ids") Collection<String> ids,
                           @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox e set status = 'PENDING', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "next_attempt_at = :nextAttemptAt, last_error = :error, updated_at = :now "
            + "where e.id = :id and case when e.status = 'PROCESSING' then e.claim_token else null end = :token", nativeQuery = true)
    int scheduleRetry(@Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt, @Param("error") String error, @Param("now") Instant now, @Param("token") String token);

    /** Return an unstarted publication without spending its reserved attempt. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox e set status = 'PENDING', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "attempts = case when e.attempts > 0 then e.attempts - 1 else 0 end, "
            + "next_attempt_at = :now, updated_at = :now "
            + "where e.id = :id and e.tenant_id = :tenantId "
            + "and case when e.status = 'PROCESSING' then e.claim_token else null end = :token", nativeQuery = true)
    int releaseUnsentClaim(@Param("id") String id, @Param("tenantId") String tenantId,
                           @Param("now") Instant now, @Param("token") String token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox e set status = 'DEAD', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "last_error = :error, updated_at = :now "
            + "where e.id = :id and case when e.status = 'PROCESSING' then e.claim_token else null end = :token", nativeQuery = true)
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
    @Query(value = "update t_ingestion_outbox e set status = 'PENDING', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "attempts = 0, next_attempt_at = :now, last_error = null, updated_at = :now "
            + "where e.id = :id and e.tenant_id = :tenantId and e.status = 'DEAD'", nativeQuery = true)
    int requeueDead(@Param("id") String id, @Param("tenantId") String tenantId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_ingestion_outbox e set status = 'DISCARDED', claim_token = null, "
            + "processing_key = null, claimed_at = null, "
            + "last_error = :reason, updated_at = :now "
            + "where e.id = :id and e.tenant_id = :tenantId and e.status = 'DEAD'", nativeQuery = true)
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
