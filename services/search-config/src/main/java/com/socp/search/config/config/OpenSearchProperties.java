package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Centralized OpenSearch connection settings shared by reader and writer. */
@ConfigurationProperties(prefix = "socp.opensearch")
public class OpenSearchProperties {

    private String url = "https://localhost:9200";
    private String username = "admin";
    private String password = "Socp!Sec2026xK";
    private boolean enabled = true;
    private String searchIndex = "socp-events-*";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSearchIndex() { return searchIndex; }
    public void setSearchIndex(String searchIndex) { this.searchIndex = searchIndex; }
}
