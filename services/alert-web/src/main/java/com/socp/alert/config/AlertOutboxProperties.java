package com.socp.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings for publishing the durable alert outbox. */
@ConfigurationProperties(prefix = "socp.alert.outbox")
public class AlertOutboxProperties {

    private int deliveryConcurrency = 4;
    private int maxAttempts = 12;
    private long retentionMs = 2_592_000_000L;

    public int getDeliveryConcurrency() { return deliveryConcurrency; }
    public void setDeliveryConcurrency(int deliveryConcurrency) { this.deliveryConcurrency = deliveryConcurrency; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetentionMs() { return retentionMs; }
    public void setRetentionMs(long retentionMs) { this.retentionMs = retentionMs; }
}
