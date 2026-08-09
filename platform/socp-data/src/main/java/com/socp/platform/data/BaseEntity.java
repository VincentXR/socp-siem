package com.socp.platform.data;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * 租户感知基类：所有业务实体继承它，自动带上 tenantId（SDK 级强制隔离，见 §3.3）。
 * 配合 TenantListener 在持久化前从 TenantContext 注入租户，避免业务层遗漏。
 */
@MappedSuperclass
public abstract class BaseEntity {
    private String tenantId;
    private Instant createdAt;
    private Instant updatedAt;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    void onCreate() {
        if (tenantId == null) {
            tenantId = com.socp.platform.tenant.TenantContext.get();
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
