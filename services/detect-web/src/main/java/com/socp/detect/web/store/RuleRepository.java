package com.socp.detect.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 检测规则仓储（H2/PG）。 */
public interface RuleRepository extends JpaRepository<RuleEntity, String> {

    List<RuleEntity> findByTenantId(String tenantId);

    Optional<RuleEntity> findByRuleIdAndTenantId(String ruleId, String tenantId);

    long countByTenantId(String tenantId);
}
