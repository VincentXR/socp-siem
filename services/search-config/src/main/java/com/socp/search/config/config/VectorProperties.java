package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed collector credential settings used when rendering Vector config. */
@ConfigurationProperties(prefix = "socp.vector")
public class VectorProperties {

    private String token = "dev-vector-token";

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
