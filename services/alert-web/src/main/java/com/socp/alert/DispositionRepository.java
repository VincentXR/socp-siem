package com.socp.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 告警处置仓库（t_alarm_disposition）。
 */
public interface DispositionRepository extends JpaRepository<DispositionEntity, String> {

    Optional<DispositionEntity> findByAlarmId(String alarmId);
}
