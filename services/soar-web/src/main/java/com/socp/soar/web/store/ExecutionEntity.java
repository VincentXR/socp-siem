package com.socp.soar.web.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable SOAR execution projection; action results survive process restart. */
@Entity
@Table(name = "t_playbook_execution", indexes = {
        @Index(name = "idx_playbook_execution_tenant_ts", columnList = "tenant_id, ts")
})
public class ExecutionEntity {

    @Id
    @Column(length = 64)
    private String executionId;

    @Column(length = 64, nullable = false)
    private String playbookId;

    @Column(length = 128)
    private String playbook;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 32)
    private String trigger;

    @Column(nullable = false)
    private int retryCount;

    @Column(length = 1024)
    private String error;

    @Column(name = "results_json", columnDefinition = "TEXT")
    private String resultsJson;

    @Column(nullable = false)
    private Instant ts;

    @Column(length = 64, nullable = false)
    private String tenantId;

    public ExecutionEntity() {}

    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public String getPlaybookId() { return playbookId; }
    public void setPlaybookId(String playbookId) { this.playbookId = playbookId; }
    public String getPlaybook() { return playbook; }
    public void setPlaybook(String playbook) { this.playbook = playbook; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getResultsJson() { return resultsJson; }
    public void setResultsJson(String resultsJson) { this.resultsJson = resultsJson; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
