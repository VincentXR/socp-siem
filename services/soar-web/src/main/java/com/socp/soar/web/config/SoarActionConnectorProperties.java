package com.socp.soar.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed endpoints for real SOAR containment and evidence adapters. */
@ConfigurationProperties(prefix = "socp.soar.action-connectors")
public class SoarActionConnectorProperties {

    private String firewallBlockUrl = "";
    private String networkIsolationUrl = "";
    private String snapshotUrl = "";
    private int timeoutMs = 5_000;

    public String getFirewallBlockUrl() { return firewallBlockUrl; }
    public void setFirewallBlockUrl(String firewallBlockUrl) { this.firewallBlockUrl = firewallBlockUrl; }
    public String getNetworkIsolationUrl() { return networkIsolationUrl; }
    public void setNetworkIsolationUrl(String networkIsolationUrl) { this.networkIsolationUrl = networkIsolationUrl; }
    public String getSnapshotUrl() { return snapshotUrl; }
    public void setSnapshotUrl(String snapshotUrl) { this.snapshotUrl = snapshotUrl; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
