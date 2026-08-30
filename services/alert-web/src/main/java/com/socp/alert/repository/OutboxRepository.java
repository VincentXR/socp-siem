package com.socp.alert.repository;

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
            + "e.lastError = null, e.updatedAt = :now where e.id = :id and e.status = 'DEAD'")
    int requeueDead(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("delete from OutboxEvent e where e.status = 'PUBLISHED' and e.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
