package com.socp.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Hard limits for one alert investigation; all remote tools remain bounded. */
@ConfigurationProperties(prefix = "socp.ai.investigation")
public class InvestigationProperties {

    private int maxToolCalls = 6;
    private int maxEvidence = 200;
    private int maxRelatedEvents = 100;
    private int timeoutMs = 15000;

    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = Math.max(1, Math.min(16, maxToolCalls)); }
    public int getMaxEvidence() { return maxEvidence; }
    public void setMaxEvidence(int maxEvidence) { this.maxEvidence = Math.max(1, Math.min(500, maxEvidence)); }
    public int getMaxRelatedEvents() { return maxRelatedEvents; }
    public void setMaxRelatedEvents(int maxRelatedEvents) { this.maxRelatedEvents = Math.max(1, Math.min(500, maxRelatedEvents)); }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = Math.max(1000, Math.min(120000, timeoutMs)); }
}
