package com.socp.soar.web.persistence.repository;


import com.socp.soar.web.persistence.entity.AlarmEvaluationEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AlarmEvaluationRepository extends TenantScopedRepository<AlarmEvaluationEntity, String> {
    java.util.List<AlarmEvaluationEntity> findByTenantId(String tenantId);
    Optional<AlarmEvaluationEntity> findByIdAndTenantId(String id, String tenantId);

    /** Serialize the processing state transition when a receipt already exists. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from AlarmEvaluationEntity e where e.id = :id and e.tenantId = :tenantId")
    Optional<AlarmEvaluationEntity> findByIdAndTenantIdForUpdate(@Param("id") String id,
                                                                  @Param("tenantId") String tenantId);
}
