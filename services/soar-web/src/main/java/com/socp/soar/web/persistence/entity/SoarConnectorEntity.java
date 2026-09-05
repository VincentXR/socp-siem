package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "t_soar_connector")
public class SoarConnectorEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(nullable = false, length = 128) private String name;
    @Column(name = "connector_type", nullable = false, length = 64) private String connectorType;
    @Column(nullable = false, length = 2048) private String endpoint;
    @Column(name = "auth_secret_ref", length = 255) private String authSecretRef;
    @Column(name = "config_json", columnDefinition = "TEXT") private String configJson;
    @Column(name = "secret_refs_json", columnDefinition = "TEXT") private String secretRefsJson;
    @Column(name = "scope_json", columnDefinition = "TEXT") private String scopeJson;
    @Column(name = "allowed_hosts_json", nullable = false, columnDefinition = "TEXT") private String allowedHostsJson;
    @Column(nullable = false) private boolean enabled;
    @Column(length = 24) private String status;
    @Column(nullable = false) private int revision;
    @Column(name = "last_test_at") private Instant lastTestAt;
    @Column(name = "last_test_status", length = 24) private String lastTestStatus;
    @Column(name = "last_test_error", length = 2048) private String lastTestError;
    @Column(name = "deleted_at") private Instant deletedAt;
    @Column(name = "created_by", nullable = false, length = 128) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private Long rowVersion;

    public SoarConnectorEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConnectorType() { return connectorType; }
    public void setConnectorType(String connectorType) { this.connectorType = connectorType; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getAuthSecretRef() { return authSecretRef; }
    public void setAuthSecretRef(String authSecretRef) { this.authSecretRef = authSecretRef; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getSecretRefsJson() { return secretRefsJson; }
    public void setSecretRefsJson(String secretRefsJson) { this.secretRefsJson = secretRefsJson; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public String getAllowedHostsJson() { return allowedHostsJson; }
    public void setAllowedHostsJson(String allowedHostsJson) { this.allowedHostsJson = allowedHostsJson; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public Instant getLastTestAt() { return lastTestAt; }
    public void setLastTestAt(Instant lastTestAt) { this.lastTestAt = lastTestAt; }
    public String getLastTestStatus() { return lastTestStatus; }
    public void setLastTestStatus(String lastTestStatus) { this.lastTestStatus = lastTestStatus; }
    public String getLastTestError() { return lastTestError; }
    public void setLastTestError(String lastTestError) { this.lastTestError = lastTestError; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
}
