package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed OpenSearch indexer runtime settings. */
@ConfigurationProperties(prefix = "socp.os-indexer")
public class OpenSearchIndexerProperties {

    private boolean enabled = true;
    private long retryBackoffMs = 1_000L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
}
