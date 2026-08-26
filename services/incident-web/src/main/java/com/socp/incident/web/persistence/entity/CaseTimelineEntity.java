package com.socp.incident.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Normalized, append-only case timeline event. */
@Entity
@Table(name = "t_case_timeline", uniqueConstraints = @UniqueConstraint(
        name = "uq_case_timeline_event", columnNames = {"tenant_id", "case_id", "event_key"}))
public class CaseTimelineEntity {

    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "case_id", nullable = false, length = 255)
    private String caseId;
    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;
    @Column(nullable = false)
    private Instant ts;
    @Column(nullable = false, length = 32)
    private String type;
    @Column(columnDefinition = "TEXT")
    private String message;
    @Column(length = 64)
    private String source;
    @Column(name = "alarm_id", length = 255)
    private String alarmId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getAlarmId() { return alarmId; }
    public void setAlarmId(String alarmId) { this.alarmId = alarmId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
