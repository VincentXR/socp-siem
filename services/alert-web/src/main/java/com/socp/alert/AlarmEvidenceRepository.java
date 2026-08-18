package com.socp.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlarmEvidenceRepository extends JpaRepository<AlarmEvidence, String> {

    List<AlarmEvidence> findByTenantIdAndAlarmIdOrderByEvidenceOrderAscIdAsc(String tenantId, String alarmId);
}
