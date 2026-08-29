package com.socp.soar.web.persistence.repository;



import com.socp.soar.web.persistence.store.*;
import com.socp.soar.web.persistence.repository.*;
import com.socp.soar.web.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;

public interface ExecutionRepository extends TenantScopedRepository<ExecutionEntity, String> {
    java.util.List<ExecutionEntity> findByTenantId(String tenantId);
    java.util.Optional<ExecutionEntity> findByExecutionIdAndTenantId(String executionId, String tenantId);

    List<ExecutionEntity> findTop200ByTenantIdOrderByTsDesc(String tenantId);
}
