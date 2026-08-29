package com.socp.detect.web.persistence.repository;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
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
            + "where o.id = :id and o.status = 'DEAD'")
    int requeueDead(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("delete from RuleChangeOutbox o where o.status = 'PUBLISHED' and o.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
