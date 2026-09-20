package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.RuleContentConflictEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped access to pending content-pack upgrade conflicts. */
public interface RuleContentConflictRepository
        extends TenantScopedRepository<RuleContentConflictEntity, String> {

    List<RuleContentConflictEntity> findByTenantIdAndStatusOrderByDetectedAtAsc(
            String tenantId, String status);

    Optional<RuleContentConflictEntity> findByTenantIdAndRuleIdAndContentPackAndPackVersion(
            String tenantId, String ruleId, String contentPack, String packVersion);
}
