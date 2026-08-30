package com.socp.alert.repository;

import com.socp.alert.domain.AlarmEvidence;


import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

public interface AlarmEvidenceRepository extends TenantScopedRepository<AlarmEvidence, String> {
    List<AlarmEvidence> findByTenantId(String tenantId);
    Optional<AlarmEvidence> findByIdAndTenantId(String id, String tenantId);

    List<AlarmEvidence> findByTenantIdAndAlarmIdOrderByEvidenceOrderAscIdAsc(String tenantId, String alarmId);
}
