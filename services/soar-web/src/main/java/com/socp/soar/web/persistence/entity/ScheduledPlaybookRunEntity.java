package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Durable, tenant-scoped claim for one scheduled playbook fire time. */
@Entity
@Table(name = "t_scheduled_playbook_run",
        uniqueConstraints = @UniqueConstraint(name = "uq_scheduled_playbook_fire",
                columnNames = {"tenant_id", "playbook_id", "scheduled_for"}),
        indexes = @Index(name = "idx_scheduled_playbook_status_updated",
                columnList = "status, updated_at"))
public class ScheduledPlaybookRunEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String playbookId;

    @Column(nullable = false)
    private Instant scheduledFor;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(length = 1024)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPlaybookId() { return playbookId; }
    public void setPlaybookId(String playbookId) { this.playbookId = playbookId; }
    public Instant getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(Instant scheduledFor) { this.scheduledFor = scheduledFor; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
