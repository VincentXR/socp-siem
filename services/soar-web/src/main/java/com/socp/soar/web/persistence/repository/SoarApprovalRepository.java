package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarApprovalEntity;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SoarApprovalRepository extends TenantScopedRepository<SoarApprovalEntity, String> {
    Optional<SoarApprovalEntity> findByTenantIdAndId(String tenantId, String id);
    /** Serialize approval decisions so two operators cannot both transition the same gate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SoarApprovalEntity a where a.tenantId = :tenantId and a.id = :id")
    Optional<SoarApprovalEntity> findByTenantIdAndIdForUpdate(@Param("tenantId") String tenantId,
                                                               @Param("id") String id);
    Optional<SoarApprovalEntity> findByTenantIdAndRunId(String tenantId, String runId);
    Optional<SoarApprovalEntity> findByTenantIdAndApprovalKey(String tenantId, String approvalKey);
    /** Serialize creation/reprojection of a concrete node-level gate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SoarApprovalEntity a where a.tenantId = :tenantId and a.approvalKey = :approvalKey")
    Optional<SoarApprovalEntity> findByTenantIdAndApprovalKeyForUpdate(
            @Param("tenantId") String tenantId, @Param("approvalKey") String approvalKey);
    List<SoarApprovalEntity> findAllByTenantIdAndRunIdOrderByCreatedAtAsc(String tenantId, String runId);
    List<SoarApprovalEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Page<SoarApprovalEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
    /** System-scope expiry scan used only by the SOAR approval janitor. */
    List<SoarApprovalEntity> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(String status,
                                                                                       Instant expiresAt);
}
