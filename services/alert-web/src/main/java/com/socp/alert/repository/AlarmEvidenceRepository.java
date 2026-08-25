package com.socp.alert.repository;

import com.socp.alert.api.controller.*;
import com.socp.alert.api.request.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlarmEvidenceRepository extends JpaRepository<AlarmEvidence, String> {

    List<AlarmEvidence> findByTenantIdAndAlarmIdOrderByEvidenceOrderAscIdAsc(String tenantId, String alarmId);
}
