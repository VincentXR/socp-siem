package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed bounds for the local hot search cache. */
@ConfigurationProperties(prefix = "socp.search.local-cache")
public class SearchCacheProperties {

    private long idleTtlMs = 1_800_000L;
    private int maxTenants = 100;

    public long getIdleTtlMs() { return idleTtlMs; }
    public void setIdleTtlMs(long idleTtlMs) { this.idleTtlMs = idleTtlMs; }
    public int getMaxTenants() { return maxTenants; }
    public void setMaxTenants(int maxTenants) { this.maxTenants = maxTenants; }
}
