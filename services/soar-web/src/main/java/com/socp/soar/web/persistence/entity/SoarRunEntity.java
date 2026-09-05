package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/** Durable V2 run projection. */
@Entity
@Table(name = "t_soar_run", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_run_request", columnNames = {"tenant_id", "request_id"}))
public class SoarRunEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;
    @Column(name = "execution_series_id", nullable = false, length = 64)
    private String executionSeriesId;
    @Column(name = "playbook_id", nullable = false, length = 64)
    private String playbookId;
    @Column(name = "playbook_version_id", nullable = false, length = 64)
    private String playbookVersionId;
    @Column(name = "playbook_version_no", nullable = false)
    private Integer playbookVersionNo;
    @Column(name = "definition_hash", nullable = false, length = 128)
    private String definitionHash;
    @Column(name = "trigger_type", nullable = false, length = 64)
    private String triggerType;
    @Column(name = "subject_type", length = 64)
    private String subjectType;
    @Column(name = "subject_id", length = 255)
    private String subjectId;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(name = "temporal_workflow_id", length = 128)
    private String temporalWorkflowId;
    @Column(name = "temporal_run_id", length = 128)
    private String temporalRunId;
    @Column(name = "input_json", columnDefinition = "TEXT")
    private String inputJson;
    @Column(name = "output_json", columnDefinition = "TEXT")
    private String outputJson;
    @Column(name = "error_code", length = 128)
    private String errorCode;
    @Column(name = "error_message", length = 2048)
    private String errorMessage;
    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public SoarRunEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getExecutionSeriesId() { return executionSeriesId; }
    public void setExecutionSeriesId(String executionSeriesId) { this.executionSeriesId = executionSeriesId; }
    public String getPlaybookId() { return playbookId; }
    public void setPlaybookId(String playbookId) { this.playbookId = playbookId; }
    public String getPlaybookVersionId() { return playbookVersionId; }
    public void setPlaybookVersionId(String playbookVersionId) { this.playbookVersionId = playbookVersionId; }
    public Integer getPlaybookVersionNo() { return playbookVersionNo; }
    public void setPlaybookVersionNo(Integer playbookVersionNo) { this.playbookVersionNo = playbookVersionNo; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String definitionHash) { this.definitionHash = definitionHash; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectId() { return subjectId; }
    public void setSubjectId(String subjectId) { this.subjectId = subjectId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTemporalWorkflowId() { return temporalWorkflowId; }
    public void setTemporalWorkflowId(String temporalWorkflowId) { this.temporalWorkflowId = temporalWorkflowId; }
    public String getTemporalRunId() { return temporalRunId; }
    public void setTemporalRunId(String temporalRunId) { this.temporalRunId = temporalRunId; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String outputJson) { this.outputJson = outputJson; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
}
