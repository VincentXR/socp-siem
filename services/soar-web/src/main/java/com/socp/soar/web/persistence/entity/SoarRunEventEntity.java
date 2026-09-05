package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Append-only, tenant-scoped run timeline event. */
@Entity
@Table(name = "t_soar_run_event", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_run_event_sequence", columnNames = {"tenant_id", "run_id", "sequence_no"}))
public class SoarRunEventEntity {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;
    @Column(name = "node_run_id", length = 64)
    private String nodeRunId;
    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;
    @Column(length = 128)
    private String actor;
    @Column(nullable = false, length = 1024)
    private String summary;
    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;
    @Column(name = "trace_id", length = 128)
    private String traceId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SoarRunEventEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getNodeRunId() { return nodeRunId; }
    public void setNodeRunId(String nodeRunId) { this.nodeRunId = nodeRunId; }
    public long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(long sequenceNo) { this.sequenceNo = sequenceNo; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
