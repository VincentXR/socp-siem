package com.socp.alert.repository;

import com.socp.alert.api.*;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;

/**
 * 告警处置仓库（t_alarm_disposition）。
 */
public interface DispositionRepository extends JpaRepository<DispositionEntity, String> {

    Optional<DispositionEntity> findByAlarmIdAndTenantId(String alarmId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DispositionEntity d where d.alarmId = :alarmId and d.tenantId = :tenantId")
    Optional<DispositionEntity> findForUpdate(@Param("alarmId") String alarmId,
                                              @Param("tenantId") String tenantId);
}
