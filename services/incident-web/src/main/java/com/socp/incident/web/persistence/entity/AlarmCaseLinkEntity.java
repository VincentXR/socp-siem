package com.socp.incident.web.persistence.entity;



import com.socp.incident.web.persistence.store.*;
import com.socp.incident.web.persistence.repository.*;
import com.socp.incident.web.persistence.entity.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "t_alarm_case_link", uniqueConstraints = {
        @UniqueConstraint(name = "uq_incident_alarm_link", columnNames = {"tenant_id", "alarm_id"})
})
public class AlarmCaseLinkEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "alarm_id", nullable = false)
    private String alarmId;

    @Column(name = "case_id", nullable = false)
    private String caseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAlarmId() { return alarmId; }
    public void setAlarmId(String alarmId) { this.alarmId = alarmId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
