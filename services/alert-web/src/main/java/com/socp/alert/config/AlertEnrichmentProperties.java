package com.socp.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded executor settings for optional threat enrichment. */
@ConfigurationProperties(prefix = "socp.alert.enrichment")
public class AlertEnrichmentProperties {

    private int concurrency = 4;
    private int queueCapacity = 1_000;

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
}
