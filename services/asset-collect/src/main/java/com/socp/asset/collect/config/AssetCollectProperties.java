package com.socp.asset.collect.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime switches for the asset collector. Simulation is never a production data source. */
@ConfigurationProperties(prefix = "socp.asset-collect")
public class AssetCollectProperties {

    private boolean simulationEnabled;

    public boolean isSimulationEnabled() {
        return simulationEnabled;
    }

    public void setSimulationEnabled(boolean simulationEnabled) {
        this.simulationEnabled = simulationEnabled;
    }
}
