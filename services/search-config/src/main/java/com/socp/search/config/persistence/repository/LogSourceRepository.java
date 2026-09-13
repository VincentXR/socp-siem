package com.socp.search.config.persistence.repository;


import com.socp.search.config.persistence.entity.LogSourceEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/** 日志源仓储。 */
public interface LogSourceRepository extends TenantScopedRepository<LogSourceEntity, String> {
    List<LogSourceEntity> findByTenantId(String tenantId);
    Page<LogSourceEntity> findByTenantId(String tenantId, Pageable pageable);
    Optional<LogSourceEntity> findByTenantIdAndSourceId(String tenantId, String sourceId);
}
