package com.socp.search.config.persistence.store;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A tenant's override or tombstone for one packaged catalog entry. */
@Entity
@Table(name = "t_tenant_catalog_entry")
public class TenantCatalogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "catalog_type", nullable = false, length = 64)
    private String catalogType;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "item_id", nullable = false, length = 255)
    private String itemId;

    @Column(nullable = false, length = 16_384)
    private String payload;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantCatalogEntry() {
    }

    TenantCatalogEntry(String catalogType, String tenantId, String itemId) {
        this.catalogType = catalogType;
        this.tenantId = tenantId;
        this.itemId = itemId;
        this.payload = "";
        this.updatedAt = Instant.now();
    }

    void savePayload(String value) {
        payload = value;
        deleted = false;
        updatedAt = Instant.now();
    }

    void markDeleted() {
        payload = "";
        deleted = true;
        updatedAt = Instant.now();
    }

    String getItemId() {
        return itemId;
    }

    String getPayload() {
        return payload;
    }

    boolean isDeleted() {
        return deleted;
    }
}
