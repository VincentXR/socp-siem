package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.RuleContentConflictEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

/** Tenant-scoped access to pending content-pack upgrade conflicts. */
public interface RuleContentConflictRepository
        extends TenantScopedRepository<RuleContentConflictEntity, String> {

    Page<RuleContentConflictEntity> findByTenantIdAndStatus(
            String tenantId, String status, Pageable pageable);

    Optional<RuleContentConflictEntity> findByTenantIdAndRuleIdAndContentPackAndPackVersion(
            String tenantId, String ruleId, String contentPack, String packVersion);
}
