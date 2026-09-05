package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** One externally observable attempt of an action node. */
@Entity
@Table(name = "t_soar_action_attempt", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_action_attempt", columnNames = {"tenant_id", "node_run_id", "attempt_no"}))
public class SoarActionAttemptEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "node_run_id", nullable = false, length = 64) private String nodeRunId;
    @Column(name = "attempt_no", nullable = false) private int attemptNo;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "request_hash", length = 128) private String requestHash;
    @Column(name = "remote_operation_id", length = 255) private String remoteOperationId;
    @Column(name = "remote_time") private Instant remoteTime;
    @Column(name = "connection_id", length = 64) private String connectionId;
    @Column(name = "connection_revision") private Integer connectionRevision;
    @Column(name = "receipt_json", columnDefinition = "TEXT") private String receiptJson;
    @Column(name = "error_code", length = 128) private String errorCode;
    @Column(name = "error_message", length = 2048) private String errorMessage;
    @Column(nullable = false) private boolean retryable;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    public SoarActionAttemptEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getNodeRunId() { return nodeRunId; }
    public void setNodeRunId(String nodeRunId) { this.nodeRunId = nodeRunId; }
    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public String getRemoteOperationId() { return remoteOperationId; }
    public void setRemoteOperationId(String remoteOperationId) { this.remoteOperationId = remoteOperationId; }
    public Instant getRemoteTime() { return remoteTime; }
    public void setRemoteTime(Instant remoteTime) { this.remoteTime = remoteTime; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public Integer getConnectionRevision() { return connectionRevision; }
    public void setConnectionRevision(Integer connectionRevision) { this.connectionRevision = connectionRevision; }
    public String getReceiptJson() { return receiptJson; }
    public void setReceiptJson(String receiptJson) { this.receiptJson = receiptJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
