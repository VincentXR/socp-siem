package com.socp.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings for publishing the durable alert outbox. */
@ConfigurationProperties(prefix = "socp.alert.outbox")
public class AlertOutboxProperties {

    private int deliveryConcurrency = 4;
    private int maxAttempts = 12;
    private long retentionMs = 2_592_000_000L;
    private int maxDrainRounds = 64;
    private long maxDrainDurationMs = 2_000L;
    private int cleanupBatchSize = 1_000;
    private int cleanupMaxBatches = 10;

    public int getDeliveryConcurrency() { return deliveryConcurrency; }
    public void setDeliveryConcurrency(int deliveryConcurrency) { this.deliveryConcurrency = deliveryConcurrency; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetentionMs() { return retentionMs; }
    public void setRetentionMs(long retentionMs) { this.retentionMs = retentionMs; }
    public int getMaxDrainRounds() { return maxDrainRounds; }
    public void setMaxDrainRounds(int maxDrainRounds) { this.maxDrainRounds = maxDrainRounds; }
    public long getMaxDrainDurationMs() { return maxDrainDurationMs; }
    public void setMaxDrainDurationMs(long maxDrainDurationMs) { this.maxDrainDurationMs = maxDrainDurationMs; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    public int getCleanupMaxBatches() { return cleanupMaxBatches; }
    public void setCleanupMaxBatches(int cleanupMaxBatches) { this.cleanupMaxBatches = cleanupMaxBatches; }
}
