package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Immutable decision/vote record for a durable SOAR approval gate. */
@Entity
@Table(name = "t_soar_approval_decision", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_approval_decision_actor",
        columnNames = {"tenant_id", "approval_id", "actor_id"}))
public class SoarApprovalDecisionEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "approval_id", nullable = false, length = 64) private String approvalId;
    @Column(name = "actor_id", nullable = false, length = 128) private String actorId;
    @Column(nullable = false, length = 16) private String decision;
    @Column(length = 2048) private String reason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public SoarApprovalDecisionEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
