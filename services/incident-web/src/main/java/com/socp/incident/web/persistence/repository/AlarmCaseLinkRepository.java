package com.socp.incident.web.persistence.repository;



import com.socp.incident.web.persistence.store.*;
import com.socp.incident.web.persistence.repository.*;
import com.socp.incident.web.persistence.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlarmCaseLinkRepository extends JpaRepository<AlarmCaseLinkEntity, String> {
    Optional<AlarmCaseLinkEntity> findByTenantIdAndAlarmId(String tenantId, String alarmId);
}
