package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarSignalOutboxEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SoarSignalOutboxRepository extends TenantScopedRepository<SoarSignalOutboxEntity, String> {
    Optional<SoarSignalOutboxEntity> findByTenantIdAndId(String tenantId, String id);
    List<SoarSignalOutboxEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);
    Optional<SoarSignalOutboxEntity> findByTenantIdAndRunIdAndSignalType(
            String tenantId, String runId, String signalType);
    Optional<SoarSignalOutboxEntity> findByTenantIdAndRunIdAndSignalTypeAndSignalKey(
            String tenantId, String runId, String signalType, String signalKey);
    long countByStatus(String status);
    long countByTenantIdAndStatus(String tenantId, String status);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SoarSignalOutboxEntity s set s.status = 'SENDING', s.claimedBy = :worker, "
            + "s.claimedAt = :now, s.updatedAt = :now, s.rowVersion = s.rowVersion + 1, "
            + "s.attempts = s.attempts + 1 where s.tenantId = :tenantId and s.id = :id "
            + "and s.status = 'PENDING' and s.rowVersion = :version and s.attempts < :maxAttempts "
            + "and s.nextAttemptAt <= :now")
    int claim(@Param("tenantId") String tenantId, @Param("id") String id,
              @Param("worker") String worker, @Param("now") Instant now,
              @Param("version") long version, @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SoarSignalOutboxEntity s set s.status = :status, s.claimedBy = null, s.claimedAt = null, "
            + "s.nextAttemptAt = :nextAttempt, s.lastError = :error, s.updatedAt = :now, s.rowVersion = s.rowVersion + 1 "
            + "where s.tenantId = :tenantId and s.id = :id and s.status = 'SENDING' "
            + "and s.rowVersion = :version and s.claimedBy = :worker")
    int completeClaim(@Param("tenantId") String tenantId, @Param("id") String id,
                      @Param("version") long version, @Param("worker") String worker,
                      @Param("status") String status, @Param("nextAttempt") Instant nextAttempt,
                      @Param("error") String error, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_soar_signal_outbox set status = case when attempts >= :maxAttempts then 'DEAD' else 'PENDING' end, "
            + "claimed_by = null, claimed_at = null, next_attempt_at = :now, updated_at = :now, row_version = row_version + 1, "
            + "last_error = 'signal claim expired before acknowledgement' where id in ( "
            + "with candidates as (select id from t_soar_signal_outbox where status = 'SENDING' and claimed_at < :staleBefore "
            + "order by claimed_at, id limit :batchSize for update skip locked) select id from candidates) "
            + "and status = 'SENDING' and claimed_at < :staleBefore", nativeQuery = true)
    int recoverStaleClaims(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now,
                           @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "update t_soar_signal_outbox set status = 'DEAD', claimed_by = null, claimed_at = null, "
            + "updated_at = :now, row_version = row_version + 1, last_error = 'signal delivery attempts exhausted' where id in ( "
            + "with candidates as (select id from t_soar_signal_outbox where status = 'PENDING' and attempts >= :maxAttempts "
            + "order by next_attempt_at, id limit :batchSize for update skip locked) select id from candidates) "
            + "and status = 'PENDING' and attempts >= :maxAttempts", nativeQuery = true)
    int markExhausted(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);
    List<SoarSignalOutboxEntity> findByTenantIdAndStatusOrderByUpdatedAtAsc(String tenantId, String status);

    /** System-scope retention purge; call only after the owning run is selected. */
    @Modifying
    @Transactional
    @Query("delete from SoarSignalOutboxEntity s where s.runId in :runIds")
    int deleteByRunIdIn(@Param("runIds") Collection<String> runIds);
}
