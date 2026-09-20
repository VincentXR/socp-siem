package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * A "content pack updated but the local rule was customized" record (Flyway
 * V22, table {@code t_rule_content_conflict}).
 *
 * <p>Raised by {@code RuleSpecStore.syncPackagedContent} when a rule is owned by
 * the packaged content set, the analyst has customized it, and the running
 * content pack advertises a newer version. Recording the pending upgrade keeps
 * the local tuning authoritative while surfacing that a newer pack version was
 * deliberately not applied, instead of silently overwriting (the pre-fix
 * behaviour) or silently skipping (no record at all).</p>
 */
@Entity
@Table(name = "t_rule_content_conflict")
public class RuleContentConflictEntity implements Persistable<String> {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "rule_id", length = 128, nullable = false)
    private String ruleId;

    @Column(name = "content_pack", length = 128, nullable = false)
    private String contentPack;

    @Column(name = "pack_version", length = 64, nullable = false)
    private String packVersion;

    @Column(name = "stored_version", length = 64)
    private String storedVersion;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public RuleContentConflictEntity() {
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getContentPack() { return contentPack; }
    public void setContentPack(String contentPack) { this.contentPack = contentPack; }
    public String getPackVersion() { return packVersion; }
    public void setPackVersion(String packVersion) { this.packVersion = packVersion; }
    public String getStoredVersion() { return storedVersion; }
    public void setStoredVersion(String storedVersion) { this.storedVersion = storedVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
