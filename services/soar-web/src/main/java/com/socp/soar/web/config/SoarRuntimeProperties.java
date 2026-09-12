package com.socp.soar.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime switches and policy for SOAR. */
@ConfigurationProperties(prefix = "socp.soar")
public class SoarRuntimeProperties {

    private boolean simulationEnabled;
    private String maturity = "preview";
    private String scheduleZone = "UTC";
    /** Whether event evaluation is enabled for this deployment. */
    private boolean evaluationEnabled = true;
    /** Whether the SOAR control plane is enabled for this deployment. */
    private boolean controlPlaneEnabled = true;
    /** Whether durable SOAR run admission is enabled for this deployment. */
    private boolean executionEnabled = true;
    /** Comma-separated tenant allow-list for execution; empty means all. */
    private String executionTenantAllowlist = "";
    /** Whether the run-event SSE endpoint is enabled for this deployment. */
    private boolean sseEnabled = true;
    /** Polling interval used by the run-event SSE projection. */
    private long ssePollIntervalMs = 500L;
    /** Maximum lifetime of an idle SSE connection. */
    private long sseTimeoutMs = 30_000L;
    /** Shared scheduler size for SSE projection callbacks. */
    private int sseSchedulerThreads = 2;

    public boolean isSimulationEnabled() { return simulationEnabled; }
    public void setSimulationEnabled(boolean simulationEnabled) { this.simulationEnabled = simulationEnabled; }
    public String getMaturity() { return maturity; }
    public void setMaturity(String maturity) { this.maturity = maturity; }
    public String getScheduleZone() { return scheduleZone; }
    public void setScheduleZone(String scheduleZone) { this.scheduleZone = scheduleZone; }
    public boolean isEvaluationEnabled() { return evaluationEnabled; }
    public void setEvaluationEnabled(boolean evaluationEnabled) { this.evaluationEnabled = evaluationEnabled; }
    public boolean isControlPlaneEnabled() { return controlPlaneEnabled; }
    public void setControlPlaneEnabled(boolean controlPlaneEnabled) { this.controlPlaneEnabled = controlPlaneEnabled; }
    public boolean isExecutionEnabled() { return executionEnabled; }
    public void setExecutionEnabled(boolean executionEnabled) { this.executionEnabled = executionEnabled; }
    public String getExecutionTenantAllowlist() { return executionTenantAllowlist; }
    public void setExecutionTenantAllowlist(String executionTenantAllowlist) { this.executionTenantAllowlist = executionTenantAllowlist; }
    public boolean isSseEnabled() { return sseEnabled; }
    public void setSseEnabled(boolean sseEnabled) { this.sseEnabled = sseEnabled; }
    public long getSsePollIntervalMs() { return ssePollIntervalMs; }
    public void setSsePollIntervalMs(long ssePollIntervalMs) { this.ssePollIntervalMs = ssePollIntervalMs; }
    public long getSseTimeoutMs() { return sseTimeoutMs; }
    public void setSseTimeoutMs(long sseTimeoutMs) { this.sseTimeoutMs = sseTimeoutMs; }
    public int getSseSchedulerThreads() { return sseSchedulerThreads; }
    public void setSseSchedulerThreads(int sseSchedulerThreads) { this.sseSchedulerThreads = sseSchedulerThreads; }
}
