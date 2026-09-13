package com.socp.asset.web.persistence.repository;


import com.socp.asset.web.persistence.entity.AssetEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 资产仓储（H2/PG）。 */
public interface AssetRepository extends TenantScopedRepository<AssetEntity, String> {

    List<AssetEntity> findByTenantId(String tenantId);

    Page<AssetEntity> findByTenantId(String tenantId, Pageable pageable);

    @Query("""
            select a from AssetEntity a
             where a.tenantId = :tenantId
               and (lower(coalesce(a.name, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(a.type, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(a.ip, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(a.os, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(a.owner, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(a.criticality, '')) like lower(concat('%', :query, '%')))
            """)
    Page<AssetEntity> searchByTenantId(@Param("tenantId") String tenantId,
                                       @Param("query") String query,
                                       Pageable pageable);

    Optional<AssetEntity> findByIdAndTenantId(String id, String tenantId);

    List<AssetEntity> findByIpAndTenantId(String ip, String tenantId);

    long countByTenantId(String tenantId);

    @Query("select a.type, count(a) from AssetEntity a where a.tenantId = :tenantId group by a.type")
    List<Object[]> countByTenantIdGroupByType(@Param("tenantId") String tenantId);

    @Query("select a.criticality, count(a) from AssetEntity a where a.tenantId = :tenantId group by a.criticality")
    List<Object[]> countByTenantIdGroupByCriticality(@Param("tenantId") String tenantId);

    @Query("select a.owner, count(a) from AssetEntity a where a.tenantId = :tenantId group by a.owner")
    List<Object[]> countByTenantIdGroupByOwner(@Param("tenantId") String tenantId);
}
