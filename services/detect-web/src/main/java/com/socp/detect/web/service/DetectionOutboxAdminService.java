package com.socp.detect.web.service;

import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
import com.socp.detect.web.persistence.repository.RuleChangeOutboxRepository;
import com.socp.platform.data.outbox.DeadOutboxRecord;
import com.socp.platform.data.outbox.OutboxAdminResult;
import com.socp.platform.error.exception.ApiException;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Tenant-scoped operator recovery for Detection's durable publisher rows. */
@Service
public class DetectionOutboxAdminService {

    private final DetectionAlertOutboxRepository alertRepository;
    private final RuleChangeOutboxRepository ruleRepository;
    private final DetectionPerformanceMetrics performanceMetrics;

    public DetectionOutboxAdminService(DetectionAlertOutboxRepository alertRepository,
                                       RuleChangeOutboxRepository ruleRepository,
                                       DetectionPerformanceMetrics performanceMetrics) {
        this.alertRepository = alertRepository;
        this.ruleRepository = ruleRepository;
        this.performanceMetrics = performanceMetrics;
    }

    @Transactional(readOnly = true)
    public List<DeadOutboxRecord> deadAlerts() {
        String tenant = TenantContext.require();
        return alertRepository.findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(tenant, "DEAD")
                .stream().map(this::alertView).toList();
    }

    @Transactional(readOnly = true)
    public List<DeadOutboxRecord> deadRuleChanges() {
        String tenant = TenantContext.require();
        return ruleRepository.findTop100ByTenantIdAndStatusOrderByUpdatedAtAsc(tenant, "DEAD")
                .stream().map(this::ruleView).toList();
    }

    @Transactional
    public OutboxAdminResult requeueAlert(String id) {
        String tenant = TenantContext.require();
        DetectionAlertOutboxEntity event = alertRepository.findByAlertIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Detection alert outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (alertRepository.requeueDead(id, tenant, now) != 1) {
            throw ApiException.badRequest("Only DEAD detection alert outbox rows can be requeued");
        }
        lifecycle("alert", "requeued");
        return new OutboxAdminResult(id, "detection_alert",
                event.alertDelivered() ? "DELIVERED" : "PENDING", now);
    }

    @Transactional
    public OutboxAdminResult requeueRuleChange(String id) {
        String tenant = TenantContext.require();
        ruleRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Rule-change outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (ruleRepository.requeueDead(id, tenant, now) != 1) {
            throw ApiException.badRequest("Only DEAD rule-change outbox rows can be requeued");
        }
        lifecycle("rule_change", "requeued");
        return new OutboxAdminResult(id, "rule_change", "PENDING", now);
    }

    @Transactional
    public OutboxAdminResult discardAlert(String id, String reason) {
        String tenant = TenantContext.require();
        DetectionAlertOutboxEntity event = alertRepository.findByAlertIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Detection alert outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (alertRepository.discardDead(id, tenant,
                discardReason(reason, event.getLastError()), now) != 1) {
            throw ApiException.badRequest("Only DEAD detection alert outbox rows can be discarded");
        }
        lifecycle("alert", "discarded");
        return new OutboxAdminResult(id, "detection_alert", "DISCARDED", now);
    }

    @Transactional
    public OutboxAdminResult discardRuleChange(String id, String reason) {
        String tenant = TenantContext.require();
        RuleChangeOutbox event = ruleRepository.findByIdAndTenantId(id, tenant)
                .orElseThrow(() -> ApiException.notFound("Rule-change outbox row does not exist: " + id));
        Instant now = Instant.now();
        if (ruleRepository.discardDead(id, tenant,
                discardReason(reason, event.getLastError()), now) != 1) {
            throw ApiException.badRequest("Only DEAD rule-change outbox rows can be discarded");
        }
        lifecycle("rule_change", "discarded");
        return new OutboxAdminResult(id, "rule_change", "DISCARDED", now);
    }

    private DeadOutboxRecord alertView(DetectionAlertOutboxEntity event) {
        return new DeadOutboxRecord(event.getAlertId(), "detection_alert", event.getAlertId(),
                event.getAttempts(), event.getCreatedAt(), event.getUpdatedAt(), event.getLastError());
    }

    private DeadOutboxRecord ruleView(RuleChangeOutbox event) {
        return new DeadOutboxRecord(event.getId(), "rule_change",
                event.getRuleId() + ':' + event.getAction(), event.getAttempts(),
                event.getCreatedAt(), event.getUpdatedAt(), event.getLastError());
    }

    private void lifecycle(String outbox, String outcome) {
        performanceMetrics.outboxLifecycle(outbox, outcome, 1);
    }

    private static String discardReason(String reason, String previousFailure) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A discard reason is required");
        }
        String value = "operator discard: " + reason.trim();
        if (previousFailure != null && !previousFailure.isBlank()) {
            value += " | previous failure: " + previousFailure.trim();
        }
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
