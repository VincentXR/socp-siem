package com.socp.detect.web.persistence.repository;

import com.socp.detect.web.persistence.entity.RuleRevisionEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.time.Instant;
import java.util.Optional;

/** Tenant-scoped access to a rule's immutable spec version chain. */
public interface RuleRevisionRepository extends TenantScopedRepository<RuleRevisionEntity, String> {

    interface Head {
        long getRevision();
        String getSource();
    }

    Optional<Head> findFirstByTenantIdAndRuleIdOrderByRevisionDesc(String tenantId, String ruleId);

    interface Summary {
        long getRevision();
        String getRuleId();
        String getStatus();
        String getSource();
        String getChangedBy();
        Instant getChangedAt();
    }

    Page<Summary> findAllByTenantIdAndRuleId(String tenantId, String ruleId, Pageable pageable);

    Slice<RuleRevisionEntity> findByTenantIdAndRuleIdOrderByRevisionAsc(
            String tenantId, String ruleId, Pageable pageable);

    Optional<RuleRevisionEntity> findByTenantIdAndRuleIdAndRevision(
            String tenantId, String ruleId, long revision);

    /** Highest recorded revision for a rule, or 0 when none exists yet. */
    @Query("select coalesce(max(r.revision), 0) from RuleRevisionEntity r "
            + "where r.tenantId = :tenant and r.ruleId = :ruleId")
    long maxRevision(@Param("tenant") String tenant, @Param("ruleId") String ruleId);
}
