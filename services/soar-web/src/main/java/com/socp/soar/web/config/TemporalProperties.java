package com.socp.soar.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed Temporal connection and worker settings. */
@ConfigurationProperties(prefix = "socp.temporal")
public class TemporalProperties {

    private boolean enabled = true;
    private String target = "localhost:7233";
    private String namespace = "default";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
}
