package com.socp.incident.web.persistence.repository;

import com.socp.incident.web.persistence.entity.CaseMutationEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import java.util.Optional;

public interface CaseMutationRepository extends TenantScopedRepository<CaseMutationEntity, String> {
    Optional<CaseMutationEntity> findByTenantIdAndCaseIdAndRequestKey(String tenantId, String caseId, String requestKey);
}
