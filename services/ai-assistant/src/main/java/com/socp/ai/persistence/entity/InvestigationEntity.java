package com.socp.ai.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Durable investigation receipt. A completed result is safe to return on replay. */
@Entity
@Table(name = "t_ai_investigation", uniqueConstraints = @UniqueConstraint(
        name = "uq_ai_investigation_tenant_alert", columnNames = {"tenant_id", "alert_id"}))
public class InvestigationEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "alert_id", nullable = false, length = 128)
    private String alertId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "incident_id", length = 255)
    private String incidentId;

    @Column(name = "appended_at")
    private Instant appendedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "claim_owner", length = 128)
    private String claimOwner;

    @Column(name = "claim_until")
    private Instant claimUntil;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String incidentId) { this.incidentId = incidentId; }
    public Instant getAppendedAt() { return appendedAt; }
    public void setAppendedAt(Instant appendedAt) { this.appendedAt = appendedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getClaimOwner() { return claimOwner; }
    public void setClaimOwner(String claimOwner) { this.claimOwner = claimOwner; }
    public Instant getClaimUntil() { return claimUntil; }
    public void setClaimUntil(Instant claimUntil) { this.claimUntil = claimUntil; }
}
