package com.socp.incident.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "t_case_rule_link", uniqueConstraints = @UniqueConstraint(
        name = "uq_case_rule_link", columnNames = {"tenant_id", "case_id", "rule_id"}))
public class CaseRuleLinkEntity {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "case_id", nullable = false)
    private String caseId;
    @Column(name = "rule_id", nullable = false)
    private String ruleId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
