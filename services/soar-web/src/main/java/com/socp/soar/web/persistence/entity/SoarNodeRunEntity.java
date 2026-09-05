package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/** Per-node execution projection. */
@Entity
@Table(name = "t_soar_node_run", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_node_run", columnNames = {"tenant_id", "run_id", "node_id", "iteration_path"}))
public class SoarNodeRunEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;
    @Column(name = "node_id", nullable = false, length = 64)
    private String nodeId;
    @Column(name = "iteration_path", nullable = false, length = 512)
    private String iterationPath;
    @Column(name = "node_type", nullable = false, length = 32)
    private String nodeType;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;
    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;
    @Column(name = "connection_id", length = 64)
    private String connectionId;
    @Column(name = "connection_revision")
    private Integer connectionRevision;
    @Column(name = "error_code", length = 128)
    private String errorCode;
    @Column(name = "error_message", length = 2048)
    private String errorMessage;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public SoarNodeRunEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getIterationPath() { return iterationPath; }
    public void setIterationPath(String iterationPath) { this.iterationPath = iterationPath; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String outputJson) { this.outputJson = outputJson; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public Integer getConnectionRevision() { return connectionRevision; }
    public void setConnectionRevision(Integer connectionRevision) { this.connectionRevision = connectionRevision; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
}
