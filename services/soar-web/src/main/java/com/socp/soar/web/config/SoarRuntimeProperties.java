package com.socp.soar.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime maturity and dry-run policy for SOAR. */
@ConfigurationProperties(prefix = "socp.soar")
public class SoarRuntimeProperties {

    private boolean simulationEnabled;
    private String maturity = "preview";
    private String scheduleZone = "UTC";
    /**
     * Single-path evaluation switch (design §8.1): when enabled, alarm evaluation
     * runs V2 automation rules only and the legacy playbook executor is skipped.
     * The legacy executor remains as a compatibility layer for tenants that
     * explicitly opt out of V2 evaluation.
     */
    private boolean v2EvaluationEnabled = true;
    /**
     * V2 control-plane switch (design §19.3).  Kept for rollout symmetry; V2
     * routes are always mounted so individual methods can be consulted when a
     * tenant has not been migrated yet.
     */
    private boolean v2ControlPlaneEnabled = true;
    /** V2 execution switch (design §19.3); false pauses V2 run admission. */
    private boolean v2ExecutionEnabled = true;
    /**
     * Legacy V1 mutation gate (design §19.3).  When false, V1 mutations return
     * HTTP 410 Gone so the time-boxed adapter cannot bypass V2 policy (for
     * example the legacy self-approval path).
     */
    private boolean legacyMutationEnabled = true;
    /** Comma-separated tenant allow-list for V2 execution; empty means all. */
    private String executionTenantAllowlist = "";

    public boolean isSimulationEnabled() { return simulationEnabled; }
    public void setSimulationEnabled(boolean simulationEnabled) { this.simulationEnabled = simulationEnabled; }
    public String getMaturity() { return maturity; }
    public void setMaturity(String maturity) { this.maturity = maturity; }
    public String getScheduleZone() { return scheduleZone; }
    public void setScheduleZone(String scheduleZone) { this.scheduleZone = scheduleZone; }
    public boolean isV2EvaluationEnabled() { return v2EvaluationEnabled; }
    public void setV2EvaluationEnabled(boolean v2EvaluationEnabled) { this.v2EvaluationEnabled = v2EvaluationEnabled; }
    public boolean isV2ControlPlaneEnabled() { return v2ControlPlaneEnabled; }
    public void setV2ControlPlaneEnabled(boolean v2ControlPlaneEnabled) { this.v2ControlPlaneEnabled = v2ControlPlaneEnabled; }
    public boolean isV2ExecutionEnabled() { return v2ExecutionEnabled; }
    public void setV2ExecutionEnabled(boolean v2ExecutionEnabled) { this.v2ExecutionEnabled = v2ExecutionEnabled; }
    public boolean isLegacyMutationEnabled() { return legacyMutationEnabled; }
    public void setLegacyMutationEnabled(boolean legacyMutationEnabled) { this.legacyMutationEnabled = legacyMutationEnabled; }
    public String getExecutionTenantAllowlist() { return executionTenantAllowlist; }
    public void setExecutionTenantAllowlist(String executionTenantAllowlist) { this.executionTenantAllowlist = executionTenantAllowlist; }
}
