package com.socp.alert.config;

import com.socp.alert.domain.AlarmDeliveryDestination;
import com.socp.alert.domain.Severity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumSet;
import java.util.Set;

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
    private Set<AlarmDeliveryDestination> destinations = EnumSet.of(AlarmDeliveryDestination.CLICKHOUSE, AlarmDeliveryDestination.NOTIFY, AlarmDeliveryDestination.INCIDENT, AlarmDeliveryDestination.SOAR);

    /**
     * Lowest severity that still creates a Case. Every alarm used to become Case
     * work, so a noisy low-severity rule could exhaust the case queue and mailbox.
     */
    private Severity caseMinSeverity = Severity.HIGH;

    /** Lowest severity that still sends a notification. */
    private Severity notifyMinSeverity = Severity.MEDIUM;

    public Severity getCaseMinSeverity() {
        return caseMinSeverity;
    }

    public void setCaseMinSeverity(Severity caseMinSeverity) {
        this.caseMinSeverity = caseMinSeverity;
    }

    public Severity getNotifyMinSeverity() {
        return notifyMinSeverity;
    }

    public void setNotifyMinSeverity(Severity notifyMinSeverity) {
        this.notifyMinSeverity = notifyMinSeverity;
    }

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
    public Set<AlarmDeliveryDestination> getDestinations() { return Set.copyOf(destinations); }
    public void setDestinations(Set<AlarmDeliveryDestination> destinations) {
        this.destinations = destinations == null || destinations.isEmpty()
                ? EnumSet.noneOf(AlarmDeliveryDestination.class) : EnumSet.copyOf(destinations);
    }
}
