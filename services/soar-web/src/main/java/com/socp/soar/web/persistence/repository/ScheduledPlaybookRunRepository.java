package com.socp.soar.web.persistence.repository;

import com.socp.soar.web.persistence.entity.ScheduledPlaybookRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScheduledPlaybookRunRepository extends JpaRepository<ScheduledPlaybookRunEntity, String> {
    Optional<ScheduledPlaybookRunEntity> findByIdAndTenantId(String id, String tenantId);
}
