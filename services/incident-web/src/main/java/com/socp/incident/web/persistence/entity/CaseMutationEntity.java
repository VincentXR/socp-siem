package com.socp.incident.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** Receipt includes no-op commands, which must remain no-ops on later retries. */
@Entity
@Table(name = "t_case_mutation", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "case_id", "request_key"}))
public class CaseMutationEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(name = "case_id", nullable = false) private String caseId;
    @Column(name = "request_key", nullable = false, length = 128) private String requestKey;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected CaseMutationEntity() { }
    public CaseMutationEntity(String tenantId, String caseId, String requestKey, String fingerprint) {
        this.id = java.util.UUID.randomUUID().toString(); this.tenantId = tenantId; this.caseId = caseId;
        this.requestKey = requestKey; this.fingerprint = fingerprint; this.createdAt = Instant.now();
    }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getFingerprint() { return fingerprint; }
}
