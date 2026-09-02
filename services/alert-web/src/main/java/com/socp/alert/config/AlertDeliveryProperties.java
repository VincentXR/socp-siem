package com.socp.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings for durable downstream alarm delivery. */
@ConfigurationProperties(prefix = "socp.alert.delivery")
public class AlertDeliveryProperties {

    private int concurrency = 8;
    private int maxAttempts = 12;
    private long retentionMs = 2_592_000_000L;
    private int maxDrainRounds = 64;
    private long maxDrainDurationMs = 2_000L;
    private int cleanupBatchSize = 1_000;
    private int cleanupMaxBatches = 10;

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
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
