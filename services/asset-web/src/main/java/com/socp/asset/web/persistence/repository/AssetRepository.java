package com.socp.asset.web.persistence.repository;


import com.socp.asset.web.persistence.entity.AssetEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
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

    @Query("""
            select a from AssetEntity a where a.tenantId = :tenantId
              and (:type = '' or upper(a.type) = upper(:type))
              and (:criticality = '' or upper(a.criticality) = upper(:criticality))
              and (:owner = '' or lower(a.owner) = lower(:owner))
              and (:query = '' or lower(concat(coalesce(a.name, ''), ' ', coalesce(a.ip, ''), ' ',
                   coalesce(a.os, ''), ' ', coalesce(a.owner, ''))) like lower(concat('%', :query, '%')))
            """)
    Page<AssetEntity> filterByTenantId(@Param("tenantId") String tenantId, @Param("query") String query,
                                       @Param("type") String type, @Param("criticality") String criticality,
                                       @Param("owner") String owner, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AssetEntity a where a.tenantId = :tenantId and lower(trim(a.ip)) = :ip")
    List<AssetEntity> findCollectionCandidate(@Param("tenantId") String tenantId, @Param("ip") String ip,
                                              Pageable pageable);

    @Query("""
            select a from AssetEntity a where a.tenantId = :tenantId
              and ((:ip <> '' and lower(trim(a.ip)) = lower(:ip))
                   or (:name <> '' and lower(trim(a.name)) = lower(:name)))
            """)
    Page<AssetEntity> findRelated(@Param("tenantId") String tenantId, @Param("ip") String ip,
                                  @Param("name") String name, Pageable pageable);

    Optional<AssetEntity> findByIdAndTenantId(String id, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AssetEntity a where a.id = :id and a.tenantId = :tenantId")
    Optional<AssetEntity> findCollectionTarget(@Param("id") String id, @Param("tenantId") String tenantId);

    long countByTenantId(String tenantId);

    @Query("select a.type, count(a) from AssetEntity a where a.tenantId = :tenantId group by a.type")
    List<Object[]> countByTenantIdGroupByType(@Param("tenantId") String tenantId);

    @Query("select a.criticality, count(a) from AssetEntity a where a.tenantId = :tenantId group by a.criticality")
    List<Object[]> countByTenantIdGroupByCriticality(@Param("tenantId") String tenantId);

    @Query("select a.owner, count(a) from AssetEntity a where a.tenantId = :tenantId group by a.owner")
    List<Object[]> countByTenantIdGroupByOwner(@Param("tenantId") String tenantId);
}
