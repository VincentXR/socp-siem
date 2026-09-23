package com.socp.search.config.persistence.repository;


import com.socp.search.config.persistence.entity.LogSourceEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 日志源仓储。 */
public interface LogSourceRepository extends TenantScopedRepository<LogSourceEntity, String> {
    List<LogSourceEntity> findByTenantId(String tenantId);
    Page<LogSourceEntity> findByTenantId(String tenantId, Pageable pageable);
    Page<LogSourceEntity> findByTenantIdAndNameContainingIgnoreCase(String tenantId, String name, Pageable pageable);
    Optional<LogSourceEntity> findByTenantIdAndSourceId(String tenantId, String sourceId);
    List<LogSourceEntity> findByTenantIdAndEnabledTrue(String tenantId);

    long countByTenantId(String tenantId);
    long countByTenantIdAndEnabledTrue(String tenantId);

    /** Identity/tag columns only: the collector-tag lookup must not deserialize every row. */
    @Query("select e.sourceId, e.name from LogSourceEntity e where e.tenantId = :tenantId")
    List<Object[]> findIdentityProjections(@Param("tenantId") String tenantId);

    @Query("select e.sourceId, e.name from LogSourceEntity e "
            + "where e.tenantId = :tenantId and e.enabled = true")
    List<Object[]> findEnabledIdentityProjections(@Param("tenantId") String tenantId);
}
