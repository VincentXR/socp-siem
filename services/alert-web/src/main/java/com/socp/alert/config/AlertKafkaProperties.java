package com.socp.alert.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Kafka settings shared by the alarm producer and registration consumer. */
@ConfigurationProperties(prefix = "socp.kafka")
public class AlertKafkaProperties {

    private String bootstrap = "localhost:9092";
    private String alarmTopic = "socp-alarm-events";
    private boolean enabled = true;

    public String getBootstrap() { return bootstrap; }
    public void setBootstrap(String bootstrap) { this.bootstrap = bootstrap; }
    public String getAlarmTopic() { return alarmTopic; }
    public void setAlarmTopic(String alarmTopic) { this.alarmTopic = alarmTopic; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
