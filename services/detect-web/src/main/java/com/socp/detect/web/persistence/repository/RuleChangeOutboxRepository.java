package com.socp.detect.web.persistence.repository;
import com.socp.detect.web.service.RuleChangeOutbox;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface RuleChangeOutboxRepository extends TenantScopedRepository<RuleChangeOutbox, String> {
    List<RuleChangeOutbox> findByTenantId(String tenantId);
    java.util.Optional<RuleChangeOutbox> findByIdAndTenantId(String id, String tenantId);

    List<RuleChangeOutbox> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant nextAttemptAt);

    List<RuleChangeOutbox> findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(
            String tenantId, String status);

    long countByStatus(String status);

    @Query("select min(o.createdAt) from RuleChangeOutbox o where o.status = :status")
    Instant findOldestCreatedAtByStatus(@Param("status") String status);

    @Query("select min(o.updatedAt) from RuleChangeOutbox o where o.status = :status")
    Instant findOldestUpdatedAtByStatus(@Param("status") String status);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'PROCESSING', o.claimedAt = :now, "
            + "o.updatedAt = :now, o.attempts = o.attempts + 1 "
            + "where o.id = :id and o.status = 'PENDING' and o.nextAttemptAt <= :now "
            + "and o.attempts < :maxAttempts")
    int claim(@Param("id") String id, @Param("now") Instant now,
              @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'PUBLISHED', o.publishedAt = :now, "
            + "o.claimedAt = null, o.lastError = null, o.updatedAt = :now "
            + "where o.id = :id and o.status = 'PROCESSING'")
    int markPublished(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'PENDING', o.nextAttemptAt = :nextAttemptAt, "
            + "o.claimedAt = null, o.lastError = :error, o.updatedAt = :now "
            + "where o.id = :id and o.status = 'PROCESSING'")
    int scheduleRetry(@Param("id") String id, @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'DEAD', o.claimedAt = null, o.lastError = :error, "
            + "o.updatedAt = :now where o.id = :id and o.status = 'PROCESSING'")
    int markDead(@Param("id") String id, @Param("error") String error, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'PENDING', o.nextAttemptAt = :now, "
            + "o.claimedAt = null, o.updatedAt = :now "
            + "where o.status = 'PROCESSING' and o.claimedAt < :cutoff")
    int recoverStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'DEAD', o.claimedAt = null, "
            + "o.lastError = coalesce(o.lastError, :reason), o.updatedAt = :now "
            + "where o.status = 'PENDING' and o.attempts >= :maxAttempts")
    int markExhausted(@Param("maxAttempts") int maxAttempts, @Param("reason") String reason,
                      @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'PENDING', o.attempts = 0, o.nextAttemptAt = :now, "
            + "o.claimedAt = null, o.lastError = null, o.updatedAt = :now "
            + "where o.id = :id and o.tenantId = :tenantId and o.status = 'DEAD'")
    int requeueDead(@Param("id") String id, @Param("tenantId") String tenantId,
                    @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("update RuleChangeOutbox o set o.status = 'DISCARDED', o.claimedAt = null, "
            + "o.lastError = :reason, o.updatedAt = :now where o.id = :id "
            + "and o.tenantId = :tenantId and o.status = 'DEAD'")
    int discardDead(@Param("id") String id, @Param("tenantId") String tenantId,
                    @Param("reason") String reason, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query(value = "delete from t_rule_change_outbox where id in ("
            + "select id from t_rule_change_outbox where status = 'PUBLISHED' and published_at < :cutoff "
            + "order by published_at asc limit :batchSize)", nativeQuery = true)
    int deletePublishedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);

    @Modifying
    @Transactional
    @Query(value = "delete from t_rule_change_outbox where id in ("
            + "select id from t_rule_change_outbox where status = 'DISCARDED' and updated_at < :cutoff "
            + "order by updated_at asc limit :batchSize)", nativeQuery = true)
    int deleteDiscardedBatchBefore(@Param("cutoff") Instant cutoff,
                                   @Param("batchSize") int batchSize);
}
