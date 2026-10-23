package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarNodeRunEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoarNodeRunRepository extends TenantScopedRepository<SoarNodeRunEntity, String> {
    List<SoarNodeRunEntity> findByTenantIdAndRunIdOrderByUpdatedAtAsc(String tenantId, String runId);
    Page<SoarNodeRunEntity> findByTenantIdAndRunIdOrderByUpdatedAtAsc(String tenantId, String runId,
                                                                       Pageable pageable);
    Optional<SoarNodeRunEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from SoarNodeRunEntity n where n.tenantId = :tenantId and n.id = :id")
    Optional<SoarNodeRunEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                              @Param("id") String id);
    Optional<SoarNodeRunEntity> findByTenantIdAndRunIdAndNodeIdAndIterationPath(
            String tenantId, String runId, String nodeId, String iterationPath);

    /** Close human waits only: RUNNING/UNKNOWN action receipts require independent reconciliation. */
    @Modifying(flushAutomatically = true)
    @Query("update SoarNodeRunEntity n set n.status = :status, n.completedAt = :at, n.updatedAt = :at, "
            + "n.errorCode = :code, n.errorMessage = 'owning run terminated while awaiting human input', "
            + "n.rowVersion = n.rowVersion + 1 where n.tenantId = :tenantId and n.runId = :runId "
            + "and n.status in ('WAITING_INPUT', 'WAITING_APPROVAL')")
    int closeHumanWaitsForRun(@Param("tenantId") String tenantId, @Param("runId") String runId,
                             @Param("status") String status, @Param("code") String code,
                             @Param("at") java.time.Instant at);

    /** System-scope retention helper: node ids owned by the given runs. */
    @Query("select n.id from SoarNodeRunEntity n where n.runId in :runIds")
    List<String> findIdsByRunIdIn(@Param("runIds") Collection<String> runIds);

    /** System-scope retention purge; call only from SoarRunRetentionWorker. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("delete from SoarNodeRunEntity n where n.runId in :runIds")
    int deleteByRunIdIn(@Param("runIds") Collection<String> runIds);
}
