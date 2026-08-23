package com.socp.alert;

import com.socp.platform.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 告警实体（对应架构 §8.2 的 alert.t_alarm + t_alarm_hist）。
 * 继承 BaseEntity 自动带 tenantId（多租户 SDK 级隔离）。
 */
@Entity
@Table(name = "t_alarm", uniqueConstraints = {
        @jakarta.persistence.UniqueConstraint(name = "uq_alarm_tenant_source_alert",
                columnNames = {"tenant_id", "source_alert_id"})
})
public class Alarm extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "rule_name")
    private String ruleName;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(length = 1024)
    private String message;

    /** 关联实体：源 IP / 主机 / 用户 */
    private String entity;

    /** 关联的 MITRE ATT&CK 技术 ID（由检测规则带入） */
    @Column(name = "mitre", length = 32)
    private String mitre;

    /** 威胁情报命中（JSON 数组，来自 threat-web 富化） */
    @Column(name = "ti_hits", length = 1024)
    private String tiHits;

    /** 威胁评分 0~100（严重级别 + ATT&CK 战术权重 + 情报命中 + 实体频次 + 资产重要性） */
    @Column(name = "risk_score")
    private Integer riskScore;

    /** 风险档位（CRITICAL/HIGH/MEDIUM/LOW/INFO），由 riskScore 分档，便于列表着色与筛选 */
    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    /** Stable id emitted by Detection; used for alert transaction idempotency. */
    @Column(name = "source_alert_id", length = 255)
    private String sourceAlertId;

    private String status = "OPEN";

    @Column(name = "occurred_at")
    private Instant occurredAt = Instant.now();

    /** Ingest timestamp of the event that completed the detection trigger. */
    @Column(name = "trigger_ingested_at")
    private Instant triggerIngestedAt;

    /** Detection-side materialization timestamp, used for pipeline latency evidence. */
    @Column(name = "alert_created_at")
    private Instant alertCreatedAt;

    @Column(name = "processing_latency_ms")
    private Long processingLatencyMs;

    @Column(name = "trigger_event_id", length = 128)
    private String triggerEventId;

    public Alarm() {
    }

    public Alarm(String ruleId, String ruleName, Severity severity, String message, String entity) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.severity = severity;
        this.message = message;
        this.entity = entity;
        this.mitre = null;
        this.tiHits = null;
    }

    public Alarm(String ruleId, String ruleName, Severity severity, String message, String entity,
                 String mitre, String tiHits) {
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.severity = severity;
        this.message = message;
        this.entity = entity;
        this.mitre = mitre;
        this.tiHits = tiHits;
    }

    public String getId() {
        return id;
    }

    void setId(String id) {
        this.id = id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getEntity() {
        return entity;
    }

    public void setEntity(String entity) {
        this.entity = entity;
    }

    public String getMitre() {
        return mitre;
    }

    public void setMitre(String mitre) {
        this.mitre = mitre;
    }

    public String getTiHits() {
        return tiHits;
    }

    public void setTiHits(String tiHits) {
        this.tiHits = tiHits;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getSourceAlertId() {
        return sourceAlertId;
    }

    public void setSourceAlertId(String sourceAlertId) {
        this.sourceAlertId = sourceAlertId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Instant getTriggerIngestedAt() {
        return triggerIngestedAt;
    }

    public void setTriggerIngestedAt(Instant triggerIngestedAt) {
        this.triggerIngestedAt = triggerIngestedAt;
    }

    public Instant getAlertCreatedAt() {
        return alertCreatedAt;
    }

    public void setAlertCreatedAt(Instant alertCreatedAt) {
        this.alertCreatedAt = alertCreatedAt;
    }

    public Long getProcessingLatencyMs() {
        return processingLatencyMs;
    }

    public void setProcessingLatencyMs(Long processingLatencyMs) {
        this.processingLatencyMs = processingLatencyMs;
    }

    public String getTriggerEventId() {
        return triggerEventId;
    }

    public void setTriggerEventId(String triggerEventId) {
        this.triggerEventId = triggerEventId;
    }
}
