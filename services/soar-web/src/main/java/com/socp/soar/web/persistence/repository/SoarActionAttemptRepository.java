package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarActionAttemptEntity;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SoarActionAttemptRepository extends TenantScopedRepository<SoarActionAttemptEntity, String> {
    List<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(String tenantId, String nodeRunId);
    Page<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdOrderByAttemptNoAsc(
            String tenantId, String nodeRunId, Pageable pageable);
    Optional<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdAndAttemptNo(
            String tenantId, String nodeRunId, int attemptNo);

    /**
     * A closed Temporal workflow is actionable-unknown only when a durable
     * action attempt was still RUNNING.  Projection failures without an
     * in-flight attempt must not be presented as an uncertain remote side
     * effect.
     */
    @Query("select case when count(a) > 0 then true else false end "
            + "from SoarActionAttemptEntity a "
            + "where a.tenantId = :tenantId and upper(a.status) = 'RUNNING' "
            + "and a.nodeRunId in (select n.id from SoarNodeRunEntity n "
            + "where n.tenantId = :tenantId and n.runId = :runId)")
    boolean existsRunningByTenantIdAndRunId(@Param("tenantId") String tenantId,
                                            @Param("runId") String runId);

    /** Serialize Activity redelivery against the attempt's business key. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SoarActionAttemptEntity a where a.tenantId = :tenantId "
            + "and a.nodeRunId = :nodeRunId and a.attemptNo = :attemptNo")
    Optional<SoarActionAttemptEntity> findByTenantIdAndNodeRunIdAndAttemptNoForUpdate(
            @Param("tenantId") String tenantId, @Param("nodeRunId") String nodeRunId,
            @Param("attemptNo") int attemptNo);

    /** System-scope retention purge; call only from SoarRunRetentionWorker. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("delete from SoarActionAttemptEntity a where a.nodeRunId in :nodeRunIds")
    int deleteByNodeRunIdIn(@Param("nodeRunIds") Collection<String> nodeRunIds);
}
