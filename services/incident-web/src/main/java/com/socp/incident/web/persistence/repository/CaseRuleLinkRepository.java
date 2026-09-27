package com.socp.incident.web.persistence.repository;

import com.socp.incident.web.persistence.entity.CaseRuleLinkEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;

public interface CaseRuleLinkRepository extends TenantScopedRepository<CaseRuleLinkEntity, String> {
    List<CaseRuleLinkEntity> findByTenantIdAndCaseIdOrderByRuleIdAsc(String tenantId, String caseId);
    List<CaseRuleLinkEntity> findByTenantIdAndCaseIdIn(String tenantId, List<String> caseIds);
}
