package com.socp.incident.web.persistence.repository;

import com.socp.incident.web.persistence.entity.CaseRuleLinkEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CaseRuleLinkRepository extends TenantScopedRepository<CaseRuleLinkEntity, String> {
    List<CaseRuleLinkEntity> findByTenantIdAndCaseIdOrderByRuleIdAsc(String tenantId, String caseId);
    List<CaseRuleLinkEntity> findByTenantIdAndCaseIdIn(String tenantId, List<String> caseIds);
    boolean existsByTenantIdAndCaseIdAndRuleId(String tenantId, String caseId, String ruleId);
    Page<CaseRuleLinkEntity> findByTenantIdAndCaseIdOrderByRuleIdAsc(
            String tenantId, String caseId, Pageable pageable);
    long countByTenantIdAndCaseId(String tenantId, String caseId);

    @Query("select r.caseId, count(r) from CaseRuleLinkEntity r "
            + "where r.tenantId = :tenantId and r.caseId in :caseIds group by r.caseId")
    List<Object[]> countByCaseIds(@Param("tenantId") String tenantId,
                                  @Param("caseIds") List<String> caseIds);

    @Modifying
    @Query(value = """
            INSERT INTO t_case_rule_link (id, tenant_id, case_id, rule_id, created_at)
            VALUES (:id, :tenantId, :caseId, :ruleId, :createdAt)
            ON CONFLICT (tenant_id, case_id, rule_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id,
                       @Param("tenantId") String tenantId,
                       @Param("caseId") String caseId,
                       @Param("ruleId") String ruleId,
                       @Param("createdAt") java.time.Instant createdAt);
}
