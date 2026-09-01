package com.socp.alert.persistence.repository;

import com.socp.alert.persistence.entity.AlarmFeedbackEntity;
import com.socp.platform.tenant.persistence.TenantScopedRepository;

import java.util.List;
import java.util.Optional;

/** Tenant-scoped persistence for false-positive and rule-exception feedback. */
public interface AlarmFeedbackRepository extends TenantScopedRepository<AlarmFeedbackEntity, String> {

    Optional<AlarmFeedbackEntity> findByTenantIdAndAlarmIdAndKind(
            String tenantId, String alarmId, String kind);

    List<AlarmFeedbackEntity> findByTenantIdAndAlarmIdOrderByCreatedAtDesc(
            String tenantId, String alarmId);
}
