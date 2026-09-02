package com.socp.alert.persistence.repository;

import com.socp.alert.domain.OutboxEvent;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbox 出站事件仓库：查询待发布事件（按时间升序，保证发布顺序与创建一致）。
 */
public interface OutboxRepository extends TenantScopedRepository<OutboxEvent, String> {
    List<OutboxEvent> findByTenantId(String tenantId);
    Optional<OutboxEvent> findByIdAndTenantId(String id, String tenantId);

    List<OutboxEvent> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant nextAttemptAt);

    List<OutboxEvent> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(String tenantId, String status);

    long countByStatus(String status);

    @Query("select min(e.createdAt) from OutboxEvent e where e.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(e.updatedAt) from OutboxEvent e where e.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'PROCESSING', e.updatedAt = :now, "
            + "e.attempts = e.attempts + 1 where e.id = :id and e.status = 'PENDING' "
            + "and e.nextAttemptAt <= :now and e.attempts < :maxAttempts")
    int claim(@Param("id") String id, @Param("now") Instant now,
              @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'PUBLISHED', e.publishedAt = :now, e.lastError = null, "
            + "e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING'")
    int markPublished(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'PENDING', e.nextAttemptAt = :nextAttemptAt, "
            + "e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING'")
    int scheduleRetry(@Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'DEAD', e.lastError = :error, e.updatedAt = :now "
            + "where e.id = :id and e.status = 'PROCESSING'")
    int markDead(@Param("id") String id, @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'PENDING', e.updatedAt = :now "
            + "where e.status = 'PROCESSING' and e.updatedAt < :cutoff")
    int recoverStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'DEAD', e.lastError = coalesce(e.lastError, :reason), "
            + "e.updatedAt = :now where e.status = 'PENDING' and e.attempts >= :maxAttempts")
    int markExhausted(@Param("maxAttempts") int maxAttempts, @Param("reason") String reason,
                      @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'PENDING', e.attempts = 0, e.nextAttemptAt = :now, "
            + "e.lastError = null, e.updatedAt = :now where e.id = :id and e.tenantId = :tenantId "
            + "and e.status = 'DEAD'")
    int requeueDead(@Param("id") String id, @Param("tenantId") String tenantId,
                    @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update OutboxEvent e set e.status = 'DISCARDED', e.lastError = :reason, e.updatedAt = :now "
            + "where e.id = :id and e.tenantId = :tenantId and e.status = 'DEAD'")
    int discardDead(@Param("id") String id, @Param("tenantId") String tenantId,
                    @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = "delete from outbox_event where id in ("
            + "select id from outbox_event where status = 'PUBLISHED' and published_at < :cutoff "
            + "order by published_at asc limit :batchSize)", nativeQuery = true)
    int deletePublishedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = "delete from outbox_event where id in ("
            + "select id from outbox_event where status = 'DISCARDED' and updated_at < :cutoff "
            + "order by updated_at asc limit :batchSize)", nativeQuery = true)
    int deleteDiscardedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);
}
