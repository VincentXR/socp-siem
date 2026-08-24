package com.socp.soc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed Kafka settings for the durable audit consumer. */
@ConfigurationProperties(prefix = "socp.kafka")
public class KafkaAuditProperties {

    private String bootstrap = "localhost:9092";
    private String auditTopic = "socp-audit";
    private boolean auditEnabled = true;

    public String getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String getAuditTopic() {
        return auditTopic;
    }

    public void setAuditTopic(String auditTopic) {
        this.auditTopic = auditTopic;
    }

    public boolean isAuditEnabled() {
        return auditEnabled;
    }

    public void setAuditEnabled(boolean auditEnabled) {
        this.auditEnabled = auditEnabled;
    }
}
