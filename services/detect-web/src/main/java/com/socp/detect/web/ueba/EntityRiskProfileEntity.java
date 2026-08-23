package com.socp.detect.web.ueba;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "t_entity_risk_profile")
class EntityRiskProfileEntity {

    @Id
    @Column(name = "entity_value", length = 512)
    private String storageId;
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;
    @Column(name = "entity_key", length = 512, nullable = false)
    private String entity;
    private double score;
    @Column(name = "score_at", nullable = false)
    private Instant scoreAt;
    @Column(name = "alert_count", nullable = false)
    private long alerts;
    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;
    @Column(name = "max_severity", length = 16, nullable = false)
    private String maxSeverity;
    @Column(name = "mitre_json", length = 8192, nullable = false)
    private String mitreJson;
    @Column(name = "rules_json", length = 8192, nullable = false)
    private String rulesJson;
    @Version
    @Column(name = "row_version", nullable = false)
    private long version;

    String getEntity() { return entity; }
    void setEntity(String entity) { this.entity = entity; }
    String getStorageId() { return storageId; }
    void setStorageId(String storageId) { this.storageId = storageId; }
    String getTenantId() { return tenantId; }
    void setTenantId(String tenantId) { this.tenantId = tenantId; }
    double getScore() { return score; }
    void setScore(double score) { this.score = score; }
    Instant getScoreAt() { return scoreAt; }
    void setScoreAt(Instant scoreAt) { this.scoreAt = scoreAt; }
    long getAlerts() { return alerts; }
    void setAlerts(long alerts) { this.alerts = alerts; }
    Instant getFirstSeen() { return firstSeen; }
    void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }
    Instant getLastSeen() { return lastSeen; }
    void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
    String getMaxSeverity() { return maxSeverity; }
    void setMaxSeverity(String maxSeverity) { this.maxSeverity = maxSeverity; }
    String getMitreJson() { return mitreJson; }
    void setMitreJson(String mitreJson) { this.mitreJson = mitreJson; }
    String getRulesJson() { return rulesJson; }
    void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
}
