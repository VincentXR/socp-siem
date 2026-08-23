package com.socp.incident.web.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlarmCaseLinkRepository extends JpaRepository<AlarmCaseLinkEntity, String> {
    Optional<AlarmCaseLinkEntity> findByTenantIdAndAlarmId(String tenantId, String alarmId);
}
