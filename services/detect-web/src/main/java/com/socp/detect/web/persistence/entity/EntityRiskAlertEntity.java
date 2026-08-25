package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "t_entity_risk_alert")
public class EntityRiskAlertEntity {
    @Id
    @Column(name = "alert_id", length = 128)
    private String storageId;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "source_alert_id", length = 128, nullable = false)
    private String alertId;
    @Column(name = "entity_value", length = 512, nullable = false)
    private String entity;
    private int score;
    @Column(name = "risk_level", length = 16, nullable = false)
    private String level;
    @Column(name = "breakdown_json", length = 2048, nullable = false)
    private String breakdownJson;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }
    public String getStorageId() { return storageId; }
    public void setStorageId(String storageId) { this.storageId = storageId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getBreakdownJson() { return breakdownJson; }
    public void setBreakdownJson(String breakdownJson) { this.breakdownJson = breakdownJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
