package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed bounds for the local hot search cache. */
@ConfigurationProperties(prefix = "socp.search.local-cache")
public class SearchCacheProperties {

    private long idleTtlMs = 1_800_000L;
    private int maxTenants = 100;
    private int warmupMaxEvents = 2_000;
    private long maxBytesPerTenant = 16L * 1024 * 1024;
    private long maxBytesTotal = 256L * 1024 * 1024;

    public long getIdleTtlMs() { return idleTtlMs; }
    public void setIdleTtlMs(long idleTtlMs) { this.idleTtlMs = idleTtlMs; }
    public int getMaxTenants() { return maxTenants; }
    public void setMaxTenants(int maxTenants) { this.maxTenants = maxTenants; }
    public int getWarmupMaxEvents() { return warmupMaxEvents; }
    public void setWarmupMaxEvents(int warmupMaxEvents) { this.warmupMaxEvents = warmupMaxEvents; }
    public long getMaxBytesPerTenant() { return maxBytesPerTenant; }
    public void setMaxBytesPerTenant(long maxBytesPerTenant) { this.maxBytesPerTenant = maxBytesPerTenant; }
    public long getMaxBytesTotal() { return maxBytesTotal; }
    public void setMaxBytesTotal(long maxBytesTotal) { this.maxBytesTotal = maxBytesTotal; }
}
