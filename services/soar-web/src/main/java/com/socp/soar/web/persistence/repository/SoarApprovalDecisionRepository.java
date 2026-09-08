package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped immutable approval decision history and vote lookup. */
public interface SoarApprovalDecisionRepository
        extends TenantScopedRepository<SoarApprovalDecisionEntity, String> {
    List<SoarApprovalDecisionEntity> findByTenantIdAndApprovalIdOrderByCreatedAtAsc(
            String tenantId, String approvalId);

    Optional<SoarApprovalDecisionEntity> findByTenantIdAndApprovalIdAndActorId(
            String tenantId, String approvalId, String actorId);

    /** System-scope retention purge; call before deleting approval gates. */
    @Modifying
    @Transactional
    @Query("delete from SoarApprovalDecisionEntity d where d.approvalId in :approvalIds")
    int deleteByApprovalIdIn(@Param("approvalIds") Collection<String> approvalIds);
}
