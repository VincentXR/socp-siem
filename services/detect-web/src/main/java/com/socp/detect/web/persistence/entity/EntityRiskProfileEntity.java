package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "t_entity_risk_profile")
public class EntityRiskProfileEntity {

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

    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public String getStorageId() { return storageId; }
    public void setStorageId(String storageId) { this.storageId = storageId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public Instant getScoreAt() { return scoreAt; }
    public void setScoreAt(Instant scoreAt) { this.scoreAt = scoreAt; }
    public long getAlerts() { return alerts; }
    public void setAlerts(long alerts) { this.alerts = alerts; }
    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
    public String getMaxSeverity() { return maxSeverity; }
    public void setMaxSeverity(String maxSeverity) { this.maxSeverity = maxSeverity; }
    public String getMitreJson() { return mitreJson; }
    public void setMitreJson(String mitreJson) { this.mitreJson = mitreJson; }
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String rulesJson) { this.rulesJson = rulesJson; }
}
