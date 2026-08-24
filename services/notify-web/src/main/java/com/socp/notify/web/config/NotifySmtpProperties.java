package com.socp.notify.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed SMTP settings used by the email notification connector. */
@ConfigurationProperties(prefix = "socp.notify.smtp")
public class NotifySmtpProperties {

    private boolean enabled;
    private String from = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }
}
