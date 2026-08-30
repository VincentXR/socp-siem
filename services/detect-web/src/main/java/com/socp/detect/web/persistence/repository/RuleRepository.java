package com.socp.detect.web.persistence.repository;


import com.socp.detect.web.persistence.entity.RuleEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

/** 检测规则仓储（H2/PG）。 */
public interface RuleRepository extends TenantScopedRepository<RuleEntity, String> {

    List<RuleEntity> findByTenantId(String tenantId);

    Optional<RuleEntity> findByRuleIdAndTenantId(String ruleId, String tenantId);

    long countByTenantId(String tenantId);
}
