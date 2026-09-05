package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarManualTaskEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SoarManualTaskRepository extends TenantScopedRepository<SoarManualTaskEntity, String> {
    List<SoarManualTaskEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Page<SoarManualTaskEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    List<SoarManualTaskEntity> findByTenantIdAndStatusOrderByDueAtAsc(String tenantId, String status);
    Optional<SoarManualTaskEntity> findByTenantIdAndId(String tenantId, String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SoarManualTaskEntity t where t.tenantId = :tenantId and t.id = :id")
    Optional<SoarManualTaskEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                                 @Param("id") String id);
    Optional<SoarManualTaskEntity> findByTenantIdAndRunIdAndNodeId(String tenantId, String runId, String nodeId);
    /** Serialize idempotent creation when a Workflow Activity is redelivered. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SoarManualTaskEntity t where t.tenantId = :tenantId and t.runId = :runId and t.nodeId = :nodeId")
    Optional<SoarManualTaskEntity> findByTenantIdAndRunIdAndNodeIdForUpdate(
            @Param("tenantId") String tenantId, @Param("runId") String runId,
            @Param("nodeId") String nodeId);
}
