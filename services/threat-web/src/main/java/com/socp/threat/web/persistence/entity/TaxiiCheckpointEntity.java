package com.socp.threat.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Last successful TAXII collection synchronization checkpoint. */
@Entity
@Table(name = "t_taxii_checkpoint")
public class TaxiiCheckpointEntity {
    @Id
    @Column(name = "checkpoint_id", length = 256)
    private String checkpointId;
    @Column(nullable = false, length = 128)
    private String feed;
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;
    @Column(name = "collection_url", nullable = false, length = 1024)
    private String collectionUrl;
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
    @Column(name = "last_page", nullable = false)
    private int lastPage;
    @Column(name = "last_error", length = 1024)
    private String lastError;

    public String getCheckpointId() { return checkpointId; }
    public void setCheckpointId(String checkpointId) { this.checkpointId = checkpointId; }
    public String getFeed() { return feed; }
    public void setFeed(String feed) { this.feed = feed; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCollectionUrl() { return collectionUrl; }
    public void setCollectionUrl(String collectionUrl) { this.collectionUrl = collectionUrl; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public int getLastPage() { return lastPage; }
    public void setLastPage(int lastPage) { this.lastPage = lastPage; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
