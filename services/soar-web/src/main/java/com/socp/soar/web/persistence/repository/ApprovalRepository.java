package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.ApprovalEntity;

import java.util.List;
import java.util.Optional;

public interface ApprovalRepository extends TenantScopedRepository<ApprovalEntity, String> {
    Optional<ApprovalEntity> findByApprovalIdAndTenantId(String approvalId, String tenantId);
    List<ApprovalEntity> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);
}
