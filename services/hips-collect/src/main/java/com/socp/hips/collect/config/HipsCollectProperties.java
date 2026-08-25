package com.socp.hips.collect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime switches for the HIPS collector. Simulation is never a production data source. */
@ConfigurationProperties(prefix = "socp.hips-collect")
public class HipsCollectProperties {

    private boolean simulationEnabled;

    public boolean isSimulationEnabled() {
        return simulationEnabled;
    }

    public void setSimulationEnabled(boolean simulationEnabled) {
        this.simulationEnabled = simulationEnabled;
    }
}
