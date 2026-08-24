package com.socp.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed ClickHouse reporter settings. */
@ConfigurationProperties(prefix = "socp.ck")
public class ClickHouseProperties {

    private String url = "http://localhost:8123";
    private String user = "default";
    private String password = "socp";
    private boolean enabled = true;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
