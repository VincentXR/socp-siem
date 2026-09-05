package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "t_soar_approval", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_approval_key", columnNames = {"tenant_id", "approval_key"}))
public class SoarApprovalEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "run_id", nullable = false, length = 64) private String runId;
    /** Stable gate identity: preflight or a concrete human-gate node. */
    @Column(name = "approval_key", nullable = false, length = 255) private String approvalKey;
    @Column(name = "node_run_id", length = 64) private String nodeRunId;
    @Column(name = "action_ref", length = 255) private String actionRef;
    @Column(name = "input_hash", length = 128) private String inputHash;
    @Column(name = "target_snapshot_json", columnDefinition = "TEXT") private String targetSnapshotJson;
    /** Immutable allow-list captured from the published gate definition. */
    @Column(name = "policy_json", columnDefinition = "TEXT") private String policyJson;
    @Column(name = "required_approvals", nullable = false) private int requiredApprovals = 1;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "requested_by", nullable = false, length = 128) private String requestedBy;
    @Column(length = 128) private String approver;
    @Column(length = 2048) private String reason;
    @Column(name = "decision_reason", length = 2048) private String decisionReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "decided_at") private Instant decidedAt;

    public SoarApprovalEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getApprovalKey() { return approvalKey; }
    public void setApprovalKey(String approvalKey) { this.approvalKey = approvalKey; }
    public String getNodeRunId() { return nodeRunId; }
    public void setNodeRunId(String nodeRunId) { this.nodeRunId = nodeRunId; }
    public String getActionRef() { return actionRef; }
    public void setActionRef(String actionRef) { this.actionRef = actionRef; }
    public String getInputHash() { return inputHash; }
    public void setInputHash(String inputHash) { this.inputHash = inputHash; }
    public String getTargetSnapshotJson() { return targetSnapshotJson; }
    public void setTargetSnapshotJson(String targetSnapshotJson) { this.targetSnapshotJson = targetSnapshotJson; }
    public String getPolicyJson() { return policyJson; }
    public void setPolicyJson(String policyJson) { this.policyJson = policyJson; }
    public int getRequiredApprovals() { return requiredApprovals; }
    public void setRequiredApprovals(int requiredApprovals) { this.requiredApprovals = Math.max(1, requiredApprovals); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public String getApprover() { return approver; }
    public void setApprover(String approver) { this.approver = approver; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
}
