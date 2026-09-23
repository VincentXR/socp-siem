package com.socp.detect.web.persistence.repository;


import com.socp.detect.web.persistence.entity.RuleEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.QueryHint;

import java.util.List;
import java.util.Optional;

/** 检测规则仓储（H2/PG）。 */
public interface RuleRepository extends TenantScopedRepository<RuleEntity, String> {

    List<RuleEntity> findByTenantId(String tenantId);

    Page<RuleEntity> findByTenantId(String tenantId, Pageable pageable);

    Optional<RuleEntity> findByRuleIdAndTenantId(String ruleId, String tenantId);

    long countByTenantId(String tenantId);

    @Query("select r from RuleEntity r where r.tenantId = :tenant "
            + "and (:keyword = '' or locate(:keyword, r.catalogSearch) > 0) "
            + "and (:status = '' or r.catalogStatus = :status) "
            + "and ((:reference = '' and :alias = '') "
            + "or (:reference <> '' and locate(:reference, r.catalogReferences) > 0) "
            + "or (:alias <> '' and locate(:alias, r.catalogReferences) > 0))")
    Page<RuleEntity> search(@Param("tenant") String tenant, @Param("keyword") String keyword,
                            @Param("status") String status, @Param("reference") String reference,
                            @Param("alias") String alias, Pageable pageable);

    @Query("select new map(r.ruleId as id, r.catalogName as name, r.catalogType as type, r.catalogStatus as status) "
            + "from RuleEntity r where r.tenantId = :tenant "
            + "and (:keyword = '' or locate(:keyword, r.catalogSearch) > 0) order by r.ruleId")
    Page<java.util.Map<String, Object>> options(@Param("tenant") String tenant,
                                              @Param("keyword") String keyword, Pageable pageable);

    @Query("select new map(r.ruleId as id, r.catalogName as name, r.catalogType as type, r.catalogStatus as status) "
            + "from RuleEntity r where r.tenantId = :tenant and r.ruleId in :ids order by r.ruleId")
    List<java.util.Map<String, Object>> lookup(@Param("tenant") String tenant, @Param("ids") List<String> ids);

    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    @Query("select distinct r.catalogTechniques from RuleEntity r where r.tenantId = :tenant "
            + "and r.catalogStatus = 'ACTIVE' and r.catalogTechniques <> '' order by r.catalogTechniques")
    List<String> activeTechniques(@Param("tenant") String tenant, Pageable pageable);
}
