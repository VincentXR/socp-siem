package com.socp.incident.web.persistence.repository;


import com.socp.incident.web.persistence.entity.CaseEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 案件仓储：所有查询强制带租户条件（多租户隔离，见 §3.3）。 */
public interface CaseRepository extends TenantScopedRepository<CaseEntity, String> {

    List<CaseEntity> findByTenantId(String tenantId);

    long countByTenantId(String tenantId);

    long countByTenantIdAndStatusIn(String tenantId, List<String> statuses);

    @Query("""
            select c from CaseEntity c
             where c.tenantId = :tenantId
               and (:status = '' or c.status = :status)
               and (:query = '' or
                    lower(coalesce(c.id, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(c.caseNo, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(c.title, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(c.entity, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(c.severity, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(c.assignee, '')) like lower(concat('%', :query, '%')))
            """)
    Page<CaseEntity> searchByTenantId(@Param("tenantId") String tenantId,
                                      @Param("query") String query,
                                      @Param("status") String status,
                                      Pageable pageable);

    Optional<CaseEntity> findByTenantIdAndId(String tenantId, String id);

    List<CaseEntity> findByTenantIdAndEntityAndStatusIn(String tenantId, String entity, List<String> statuses);

    @Modifying
    @Transactional
    @Query("update CaseEntity c set c.updatedAt = :updatedAt, c.rowVersion = c.rowVersion + 1 "
            + "where c.tenantId = :tenant and c.id = :id")
    int touchUpdatedAt(@Param("tenant") String tenant, @Param("id") String id,
                       @Param("updatedAt") Instant updatedAt);
}
