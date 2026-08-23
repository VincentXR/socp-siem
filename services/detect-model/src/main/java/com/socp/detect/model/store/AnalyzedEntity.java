package com.socp.detect.model.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 二次分析研判记录持久化实体（t_analyzed）。 */
@Entity
@Table(name = "t_analyzed")
public class AnalyzedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "alert_id", length = 64) private String alertId;
    @Column(name = "rule_id", length = 128) private String ruleId;
    @Column(name = "rule_name", length = 256) private String ruleName;
    @Column(length = 16) private String severity;
    @Column(length = 1024) private String message;
    @Column(length = 256) private String entity;
    @Column(nullable = false) private Instant ts;

    public AnalyzedEntity() {}
    public AnalyzedEntity(String tenantId, String alertId, String ruleId, String ruleName,
                          String severity, String message, String entity, Instant ts) {
        this.tenantId = tenantId; this.alertId = alertId; this.ruleId = ruleId; this.ruleName = ruleName;
        this.severity = severity; this.message = message; this.entity = entity; this.ts = ts;
    }
    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getAlertId() { return alertId; }
    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public String getSeverity() { return severity; }
    public String getMessage() { return message; }
    public String getEntity() { return entity; }
    public Instant getTs() { return ts; }
}
