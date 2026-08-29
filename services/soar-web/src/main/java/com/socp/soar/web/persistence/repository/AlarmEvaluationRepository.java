package com.socp.soar.web.persistence.repository;



import com.socp.soar.web.persistence.store.*;
import com.socp.soar.web.persistence.repository.*;
import com.socp.soar.web.persistence.entity.*;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;

public interface AlarmEvaluationRepository extends TenantScopedRepository<AlarmEvaluationEntity, String> {
    java.util.List<AlarmEvaluationEntity> findByTenantId(String tenantId);
    Optional<AlarmEvaluationEntity> findByIdAndTenantId(String id, String tenantId);
}
