package com.socp.soar.web.persistence.repository;

import com.socp.platform.tenant.persistence.TenantScopedRepository;
import com.socp.soar.web.persistence.entity.SoarTriggerReceiptEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SoarTriggerReceiptRepository extends TenantScopedRepository<SoarTriggerReceiptEntity, String> {
    Optional<SoarTriggerReceiptEntity> findByTenantIdAndEventIdAndAutomationRuleIdAndRuleRevision(
            String tenantId, String eventId, String automationRuleId, int ruleRevision);
    List<SoarTriggerReceiptEntity> findByTenantIdAndEventIdOrderByCreatedAtAsc(String tenantId, String eventId);
    List<SoarTriggerReceiptEntity> findByTenantIdAndAutomationRuleIdAndCreatedAtAfter(
            String tenantId, String automationRuleId, Instant createdAt);
    long countByTenantIdAndStatus(String tenantId, String status);
}
