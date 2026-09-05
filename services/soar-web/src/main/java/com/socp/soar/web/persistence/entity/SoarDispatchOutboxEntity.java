package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.Transient;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;

/** Durable hand-off from the HTTP transaction to Temporal. */
@Entity
@Table(name = "t_soar_dispatch_outbox", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_dispatch_run", columnNames = {"tenant_id", "run_id"}))
public class SoarDispatchOutboxEntity implements Persistable<String> {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "claimed_by", length = 128)
    private String claimedBy;
    @Column(name = "claimed_at")
    private Instant claimedAt;
    @Column(name = "last_error", length = 2048)
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public SoarDispatchOutboxEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
    @Override
    @Transient
    public boolean isNew() {
        return rowVersion == null;
    }

}
