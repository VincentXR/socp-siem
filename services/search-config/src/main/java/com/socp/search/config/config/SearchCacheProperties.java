package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed bounds for the local hot search cache. */
@ConfigurationProperties(prefix = "socp.search.local-cache")
public class SearchCacheProperties {

    private long idleTtlMs = 1_800_000L;
    private int maxTenants = 100;
    private int warmupMaxEvents = 2_000;
    private int warmupBatchSize = 128;
    private int maxConcurrentWarmups = 2;
    private long maxBytesPerTenant = 16L * 1024 * 1024;
    private long maxBytesTotal = 256L * 1024 * 1024;

    public void validate() {
        if (maxTenants < 1) throw new IllegalArgumentException("socp.search.local-cache.max-tenants must be >= 1");
        if (warmupMaxEvents < 1) throw new IllegalArgumentException("socp.search.local-cache.warmup-max-events must be >= 1");
        if (warmupBatchSize < 1) throw new IllegalArgumentException("socp.search.local-cache.warmup-batch-size must be >= 1");
        if (maxConcurrentWarmups < 1) throw new IllegalArgumentException("socp.search.local-cache.max-concurrent-warmups must be >= 1");
        if (maxBytesPerTenant < 1) throw new IllegalArgumentException("socp.search.local-cache.max-bytes-per-tenant must be >= 1");
        if (maxBytesTotal < 1) throw new IllegalArgumentException("socp.search.local-cache.max-bytes-total must be >= 1");
        if (maxBytesTotal < maxBytesPerTenant) {
            throw new IllegalArgumentException(
                    "socp.search.local-cache.max-bytes-total must be >= max-bytes-per-tenant");
        }
    }

    public long getIdleTtlMs() { return idleTtlMs; }
    public void setIdleTtlMs(long idleTtlMs) { this.idleTtlMs = idleTtlMs; }
    public int getMaxTenants() { return maxTenants; }
    public void setMaxTenants(int maxTenants) { this.maxTenants = maxTenants; }
    public int getWarmupMaxEvents() { return warmupMaxEvents; }
    public void setWarmupMaxEvents(int warmupMaxEvents) { this.warmupMaxEvents = warmupMaxEvents; }
    public int getWarmupBatchSize() { return warmupBatchSize; }
    public void setWarmupBatchSize(int warmupBatchSize) { this.warmupBatchSize = warmupBatchSize; }
    public int getMaxConcurrentWarmups() { return maxConcurrentWarmups; }
    public void setMaxConcurrentWarmups(int maxConcurrentWarmups) { this.maxConcurrentWarmups = maxConcurrentWarmups; }
    public long getMaxBytesPerTenant() { return maxBytesPerTenant; }
    public void setMaxBytesPerTenant(long maxBytesPerTenant) { this.maxBytesPerTenant = maxBytesPerTenant; }
    public long getMaxBytesTotal() { return maxBytesTotal; }
    public void setMaxBytesTotal(long maxBytesTotal) { this.maxBytesTotal = maxBytesTotal; }
}
