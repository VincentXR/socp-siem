package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds for configuration caches on the ingest path.
 *
 * <p>The TTL is the convergence window with configuration written through another SEARCH
 * replica: a local mutation invalidates immediately, a remote one becomes visible within
 * one TTL because the durable read is always the authority.
 */
@ConfigurationProperties(prefix = "socp.search.config-cache")
public class ConfigCacheProperties {

    private long ttlMs = 60_000L;

    public long getTtlMs() { return ttlMs; }
    public void setTtlMs(long ttlMs) { this.ttlMs = ttlMs; }
}
