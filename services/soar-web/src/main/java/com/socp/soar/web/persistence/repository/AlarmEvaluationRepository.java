package com.socp.soar.web.persistence.repository;



import com.socp.soar.web.persistence.store.*;
import com.socp.soar.web.persistence.repository.*;
import com.socp.soar.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlarmEvaluationRepository extends JpaRepository<AlarmEvaluationEntity, String> {
    Optional<AlarmEvaluationEntity> findByIdAndTenantId(String id, String tenantId);
}
