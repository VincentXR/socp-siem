package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed collector credential settings used when rendering Vector config. */
@ConfigurationProperties(prefix = "socp.vector")
public class VectorProperties {

    private String token = "dev-vector-token";

    /**
     * Platform SEARCH ingest endpoint rendered into generated Vector sinks.
     * Deliberately empty by default: an unconfigured platform target makes the
     * renderer fail with HTTP 409 instead of emitting a loopback address.
     */
    private String uri = "";

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
}
