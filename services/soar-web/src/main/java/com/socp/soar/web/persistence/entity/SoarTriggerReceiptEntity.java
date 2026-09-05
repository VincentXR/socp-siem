package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Durable result of evaluating one automation rule against one event. */
@Entity
@Table(name = "t_soar_trigger_receipt", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_trigger_receipt",
        columnNames = {"tenant_id", "event_id", "automation_rule_id", "rule_revision"}))
public class SoarTriggerReceiptEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "event_id", nullable = false, length = 255) private String eventId;
    @Column(name = "automation_rule_id", nullable = false, length = 64) private String automationRuleId;
    @Column(name = "rule_revision", nullable = false) private int ruleRevision;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "run_id", length = 64) private String runId;
    @Column(length = 2048) private String reason;
    @Column(name = "group_key", length = 512) private String groupKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public SoarTriggerReceiptEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getAutomationRuleId() { return automationRuleId; }
    public void setAutomationRuleId(String automationRuleId) { this.automationRuleId = automationRuleId; }
    public int getRuleRevision() { return ruleRevision; }
    public void setRuleRevision(int ruleRevision) { this.ruleRevision = ruleRevision; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
