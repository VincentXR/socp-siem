package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Limits for the raw NDJSON collector boundary. */
@ConfigurationProperties(prefix = "socp.ingest.limits")
public class IngestLimitsProperties {

    private int maxBodyBytes = 16 * 1024 * 1024;
    private int maxEvents = 1000;
    private int maxEventBytes = 256 * 1024;

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public int getMaxEvents() {
        return maxEvents;
    }

    public void setMaxEvents(int maxEvents) {
        this.maxEvents = maxEvents;
    }

    public int getMaxEventBytes() {
        return maxEventBytes;
    }

    public void setMaxEventBytes(int maxEventBytes) {
        this.maxEventBytes = maxEventBytes;
    }
}
