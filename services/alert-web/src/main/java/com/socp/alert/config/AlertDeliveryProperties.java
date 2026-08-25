package com.socp.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings for durable downstream alarm delivery. */
@ConfigurationProperties(prefix = "socp.alert.delivery")
public class AlertDeliveryProperties {

    private int concurrency = 8;
    private int maxAttempts = 12;
    private long retentionMs = 2_592_000_000L;

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetentionMs() { return retentionMs; }
    public void setRetentionMs(long retentionMs) { this.retentionMs = retentionMs; }
}
