package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed Kafka transport settings shared by the search event producer and indexer. */
@ConfigurationProperties(prefix = "socp.kafka")
public class KafkaProperties {

    private String bootstrap = "localhost:9092";
    private String topic = "socp-events";
    private boolean enabled = true;

    public String getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
