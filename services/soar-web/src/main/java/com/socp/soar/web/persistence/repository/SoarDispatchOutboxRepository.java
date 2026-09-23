package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarDispatchOutboxEntity;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SoarDispatchOutboxRepository extends TenantScopedRepository<SoarDispatchOutboxEntity, String> {
    Optional<SoarDispatchOutboxEntity> findByTenantIdAndId(String tenantId, String id);
    Optional<SoarDispatchOutboxEntity> findByTenantIdAndRunId(String tenantId, String runId);
    List<SoarDispatchOutboxEntity> findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            String status, Instant now);
    long countByStatus(String status);
    long countByTenantIdAndStatusAndNextAttemptAtLessThanEqual(String tenantId, String status, Instant now);
    /** Caller owns the transaction, including the run projection update. */
    @Query(value = "select * from t_soar_dispatch_outbox where tenant_id = :tenantId and id = :id "
            + "for update skip locked", nativeQuery = true)
    Optional<SoarDispatchOutboxEntity> findByTenantIdAndIdForUpdateSkipLocked(
            @Param("tenantId") String tenantId, @Param("id") String id);

    /** Bounded candidates only; the coordinator rechecks each version in its own transaction. */
    @Transactional
    @Query(value = "select * from t_soar_dispatch_outbox where "
            + "(status = 'DISPATCHING' and claimed_at < :staleBefore) "
            + "or (status = 'PENDING' and attempts >= :maxAttempts) "
            + "order by updated_at, id limit :batchSize for update skip locked", nativeQuery = true)
    List<SoarDispatchOutboxEntity> findRecoveryCandidates(@Param("staleBefore") Instant staleBefore,
            @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);
    List<SoarDispatchOutboxEntity> findByTenantIdAndStatusOrderByUpdatedAtAsc(String tenantId, String status);

    /** System-scope retention purge; call only after the owning run is selected. */
    @Modifying
    @Transactional
    @Query("delete from SoarDispatchOutboxEntity o where o.runId in :runIds")
    int deleteByRunIdIn(@Param("runIds") Collection<String> runIds);
}
