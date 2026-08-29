package com.socp.search.config.persistence.repository;



import com.socp.search.config.persistence.store.*;
import com.socp.search.config.persistence.repository.*;
import com.socp.search.config.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

/** 日志源仓储。 */
public interface LogSourceRepository extends TenantScopedRepository<LogSourceEntity, String> {
    List<LogSourceEntity> findByTenantId(String tenantId);
    Optional<LogSourceEntity> findByTenantIdAndSourceId(String tenantId, String sourceId);
}
