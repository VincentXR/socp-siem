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

/** Immutable published definition or optimistic-locked draft. */
@Entity
@Table(name = "t_soar_playbook_version", uniqueConstraints = @UniqueConstraint(
        name = "uq_soar_playbook_version", columnNames = {"tenant_id", "playbook_id", "version_no"}))
public class PlaybookVersionEntity implements Persistable<String> {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "playbook_id", nullable = false, length = 64)
    private String playbookId;
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "schema_version", nullable = false, length = 64)
    private String schemaVersion;
    @Column(name = "definition_json", nullable = false, columnDefinition = "TEXT")
    private String definitionJson;
    @Column(name = "layout_json", columnDefinition = "TEXT")
    private String layoutJson;
    @Column(name = "definition_hash", nullable = false, length = 128)
    private String definitionHash;
    @Column(name = "risk_summary_json", columnDefinition = "TEXT")
    private String riskSummaryJson;
    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;
    @Column(name = "published_by", length = 128)
    private String publishedBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    public PlaybookVersionEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getPlaybookId() { return playbookId; }
    public void setPlaybookId(String playbookId) { this.playbookId = playbookId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public String getLayoutJson() { return layoutJson; }
    public void setLayoutJson(String layoutJson) { this.layoutJson = layoutJson; }
    public String getDefinitionHash() { return definitionHash; }
    public void setDefinitionHash(String definitionHash) { this.definitionHash = definitionHash; }
    public String getRiskSummaryJson() { return riskSummaryJson; }
    public void setRiskSummaryJson(String riskSummaryJson) { this.riskSummaryJson = riskSummaryJson; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
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
