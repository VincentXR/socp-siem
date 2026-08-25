package com.socp.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Product maturity marker exposed by the AI assistant health endpoint. */
@ConfigurationProperties(prefix = "socp.ai")
public class AiRuntimeProperties {

    private String maturity = "preview";

    public String getMaturity() { return maturity; }
    public void setMaturity(String maturity) { this.maturity = maturity; }
}
