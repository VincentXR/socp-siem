package com.socp.hips.web.persistence.repository;


import com.socp.hips.web.persistence.entity.EndpointEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public interface EndpointRepository extends TenantScopedRepository<EndpointEntity, String> {
    List<EndpointEntity> findByTenantId(String tenantId);
    Page<EndpointEntity> findByTenantId(String tenantId, Pageable pageable);

    @Query("""
            select e from EndpointEntity e
             where e.tenantId = :tenantId
               and (lower(coalesce(e.hostname, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(e.ip, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(e.os, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(e.agentVersion, '')) like lower(concat('%', :query, '%'))
                    or lower(coalesce(e.status, '')) like lower(concat('%', :query, '%')))
            """)
    Page<EndpointEntity> searchByTenantId(@Param("tenantId") String tenantId,
                                          @Param("query") String query,
                                          Pageable pageable);

    @Query("""
            select e from EndpointEntity e
             where e.tenantId = :tenantId
               and ((:ip <> '' and lower(trim(e.ip)) = lower(:ip))
                    or (:hostname <> '' and lower(trim(e.hostname)) = lower(:hostname)))
            """)
    Page<EndpointEntity> findRelatedByTenantId(@Param("tenantId") String tenantId,
                                              @Param("ip") String ip,
                                              @Param("hostname") String hostname,
                                              Pageable pageable);

    Optional<EndpointEntity> findByStorageIdAndTenantId(String storageId, String tenantId);
    Optional<EndpointEntity> findByTenantIdAndEndpointId(String tenantId, String endpointId);
    Optional<EndpointEntity> findFirstByTenantIdAndHostname(String tenantId, String hostname);
    long countByTenantId(String tenantId);

    @Query("""
            select count(e) from EndpointEntity e
             where e.tenantId = :tenantId
               and e.status = 'ONLINE'
               and (e.lastHeartbeat is null or e.lastHeartbeat >= :cutoff)
            """)
    long countOnlineByTenantId(@Param("tenantId") String tenantId, @Param("cutoff") Instant cutoff);
}
