package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable approval request for high-impact SOAR actions. */
@Entity
@Table(name = "t_playbook_approval", indexes = {
        @Index(name = "idx_playbook_approval_tenant_status", columnList = "tenant_id, status, expires_at")
})
public class ApprovalEntity {
    @Id
    @Column(name = "approval_id", length = 64)
    private String approvalId;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "playbook_id", nullable = false, length = 64)
    private String playbookId;
    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;
    @Column(name = "approved_by", length = 128)
    private String approvedBy;
    @Column(length = 1024)
    private String reason;
    @Column(name = "scope_json", columnDefinition = "TEXT")
    private String scopeJson;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "execution_id", length = 64)
    private String executionId;

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPlaybookId() { return playbookId; }
    public void setPlaybookId(String playbookId) { this.playbookId = playbookId; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
}
