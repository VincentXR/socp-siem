package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed ingest pipeline, monitor, and outbox runtime settings. */
@ConfigurationProperties(prefix = "socp.ingest")
public class IngestRuntimeProperties {

    private boolean forwardHttp;
    private final Monitor monitor = new Monitor();
    private final Outbox outbox = new Outbox();

    public boolean isForwardHttp() { return forwardHttp; }
    public void setForwardHttp(boolean forwardHttp) { this.forwardHttp = forwardHttp; }
    public Monitor getMonitor() { return monitor; }
    public Outbox getOutbox() { return outbox; }

    public static class Monitor {
        private long idleTtlMs = 86_400_000L;
        private int maxEntries = 10_000;

        public long getIdleTtlMs() { return idleTtlMs; }
        public void setIdleTtlMs(long idleTtlMs) { this.idleTtlMs = idleTtlMs; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
    }

    public static class Outbox {
        private int deliveryConcurrency = 8;
        private int maxAttempts = 12;
        private long retentionMs = 2_592_000_000L;

        public int getDeliveryConcurrency() { return deliveryConcurrency; }
        public void setDeliveryConcurrency(int deliveryConcurrency) { this.deliveryConcurrency = deliveryConcurrency; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getRetentionMs() { return retentionMs; }
        public void setRetentionMs(long retentionMs) { this.retentionMs = retentionMs; }
    }
}
