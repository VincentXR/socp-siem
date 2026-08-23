package com.socp.soar.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlarmEvaluationRepository extends JpaRepository<AlarmEvaluationEntity, String> {
    Optional<AlarmEvaluationEntity> findByIdAndTenantId(String id, String tenantId);
}
