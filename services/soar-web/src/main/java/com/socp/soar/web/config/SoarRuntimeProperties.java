package com.socp.soar.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime maturity and dry-run policy for SOAR. */
@ConfigurationProperties(prefix = "socp.soar")
public class SoarRuntimeProperties {

    private boolean simulationEnabled;
    private boolean demoDataEnabled = true;
    private String maturity = "preview";

    public boolean isSimulationEnabled() { return simulationEnabled; }
    public void setSimulationEnabled(boolean simulationEnabled) { this.simulationEnabled = simulationEnabled; }
    public boolean isDemoDataEnabled() { return demoDataEnabled; }
    public void setDemoDataEnabled(boolean demoDataEnabled) { this.demoDataEnabled = demoDataEnabled; }
    public String getMaturity() { return maturity; }
    public void setMaturity(String maturity) { this.maturity = maturity; }
}
