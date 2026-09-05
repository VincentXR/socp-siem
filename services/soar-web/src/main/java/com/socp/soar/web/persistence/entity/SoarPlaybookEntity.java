package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.Transient;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** SOAR 2.0 playbook metadata. Runtime logic lives in immutable version rows. */
@Entity
@Table(name = "t_soar_playbook")
public class SoarPlaybookEntity implements Persistable<String> {
    @Id
    @Column(length = 64)
    private String id;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(nullable = false, length = 128)
    private String name;
    @Column(length = 2048)
    private String description;
    @Column(length = 128)
    private String owner;
    @Column(name = "tags_json", columnDefinition = "TEXT")
    private String tagsJson;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "latest_published_version")
    private Integer latestPublishedVersion;
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SoarPlaybookEntity() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getLatestPublishedVersion() { return latestPublishedVersion; }
    public void setLatestPublishedVersion(Integer latestPublishedVersion) { this.latestPublishedVersion = latestPublishedVersion; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    @Override
    @Transient
    public boolean isNew() {
        return rowVersion == null;
    }

}
