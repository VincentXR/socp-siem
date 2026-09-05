package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarApprovalDecisionEntity;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped immutable approval decision history and vote lookup. */
public interface SoarApprovalDecisionRepository
        extends TenantScopedRepository<SoarApprovalDecisionEntity, String> {
    List<SoarApprovalDecisionEntity> findByTenantIdAndApprovalIdOrderByCreatedAtAsc(
            String tenantId, String approvalId);

    Optional<SoarApprovalDecisionEntity> findByTenantIdAndApprovalIdAndActorId(
            String tenantId, String approvalId, String actorId);
}
