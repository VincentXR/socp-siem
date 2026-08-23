package com.socp.soc.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 审计记录持久化实体（t_audit，由 Flyway V1 建表）。 */
@Entity
@Table(name = "t_audit")
public class AuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 128, nullable = false, unique = true)
    private String eventId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "action", length = 128, nullable = false)
    private String action;

    @Column(name = "operator", length = 128, nullable = false)
    private String operator;

    @Column(name = "target", length = 512)
    private String target;

    @Column(name = "result", length = 64, nullable = false)
    private String result;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    public AuditEntity() {
    }

    public AuditEntity(String eventId, String tenantId, String action, String operator, String target,
                       String result, Instant ts) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.action = action;
        this.operator = operator;
        this.target = target;
        this.result = result;
        this.ts = ts;
    }

    public AuditEntity(String tenantId, String action, String operator, String target,
                       String result, Instant ts) {
        this(java.util.UUID.randomUUID().toString(), tenantId, action, operator, target, result, ts);
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public String getTenantId() { return tenantId; }
    public String getAction() { return action; }
    public String getOperator() { return operator; }
    public String getTarget() { return target; }
    public String getResult() { return result; }
    public Instant getTs() { return ts; }
}
