package com.socp.detect.web.persistence.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/** Durable tenant-owned watchlist overlay or template tombstone. */
@Entity
@Table(name = "t_watchlist")
public class WatchlistEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "list_name", nullable = false, length = 255)
    private String listName;

    // PostgreSQL TEXT and H2 TEXT both validate as VARCHAR through Hibernate's
    // JDBC metadata; avoid @Lob here because it requests a CLOB mapping.
    @Column(name = "values_json", nullable = false, length = 16_384)
    private String valuesJson;

    @Column(nullable = false)
    private boolean deleted;

    @Version
    @Column(nullable = false)
    private long rowVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WatchlistEntity() {
    }

    public WatchlistEntity(String tenantId, String listName) {
        this.tenantId = tenantId;
        this.listName = listName;
        this.valuesJson = "[]";
        this.updatedAt = Instant.now();
    }

    public void saveValues(String valuesJson) {
        this.valuesJson = valuesJson;
        this.deleted = false;
        this.updatedAt = Instant.now();
    }

    public void markDeleted() {
        this.valuesJson = "[]";
        this.deleted = true;
        this.updatedAt = Instant.now();
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getListName() {
        return listName;
    }

    public String getValuesJson() {
        return valuesJson;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
