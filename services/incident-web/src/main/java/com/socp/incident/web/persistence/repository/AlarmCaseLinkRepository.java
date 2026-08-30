package com.socp.incident.web.persistence.repository;


import com.socp.incident.web.persistence.entity.AlarmCaseLinkEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.Optional;
import java.util.List;

public interface AlarmCaseLinkRepository extends TenantScopedRepository<AlarmCaseLinkEntity, String> {
    List<AlarmCaseLinkEntity> findByTenantId(String tenantId);
    Optional<AlarmCaseLinkEntity> findByIdAndTenantId(String id, String tenantId);
    Optional<AlarmCaseLinkEntity> findByTenantIdAndAlarmId(String tenantId, String alarmId);
}
