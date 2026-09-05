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

    public boolean isSimulationEnabled() { return simulationEnabled; }
    public void setSimulationEnabled(boolean simulationEnabled) { this.simulationEnabled = simulationEnabled; }
    public String getMaturity() { return maturity; }
    public void setMaturity(String maturity) { this.maturity = maturity; }
    public String getScheduleZone() { return scheduleZone; }
    public void setScheduleZone(String scheduleZone) { this.scheduleZone = scheduleZone; }
    public boolean isV2EvaluationEnabled() { return v2EvaluationEnabled; }
    public void setV2EvaluationEnabled(boolean v2EvaluationEnabled) { this.v2EvaluationEnabled = v2EvaluationEnabled; }
}
