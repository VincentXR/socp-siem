package com.socp.detect.web.ueba;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "t_entity_risk_alert")
class EntityRiskAlertEntity {
    @Id
    @Column(name = "alert_id", length = 128)
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

    String getAlertId() { return alertId; }
    void setAlertId(String alertId) { this.alertId = alertId; }
    String getEntity() { return entity; }
    void setEntity(String entity) { this.entity = entity; }
    int getScore() { return score; }
    void setScore(int score) { this.score = score; }
    String getLevel() { return level; }
    void setLevel(String level) { this.level = level; }
    String getBreakdownJson() { return breakdownJson; }
    void setBreakdownJson(String breakdownJson) { this.breakdownJson = breakdownJson; }
    Instant getCreatedAt() { return createdAt; }
    void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
