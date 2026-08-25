package com.socp.alert.service;

import com.socp.alert.api.*;
import com.socp.alert.config.AlertKafkaProperties;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Publishes alert Outbox rows to the durable fan-out topic. */
@Component
public class AlertKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlertKafkaPublisher.class);

    private final String bootstrap;
    private final String topic;
    private final boolean enabled;

    private volatile KafkaProducer<String, String> producer;

    @Autowired
    public AlertKafkaPublisher(AlertKafkaProperties properties) {
        this.bootstrap = properties.getBootstrap();
        this.topic = properties.getAlarmTopic();
        this.enabled = properties.isEnabled();
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    Properties props = new Properties();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.ACKS_CONFIG, "all");
                    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
                    props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
                    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
                    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /** Compatibility fire-and-forget API for callers outside the Outbox publisher. */
    public void sendAlarmEvent(String alarmId, String payload) {
        Thread.startVirtualThread(() -> sendAlarmEventAndAwait(alarmId, payload));
    }

    /**
     * Wait for the broker acknowledgement. The caller must only mark the
     * database Outbox row published when this method returns true.
     */
    public boolean sendAlarmEventAndAwait(String alarmId, String payload) {
        if (!enabled || payload == null) return false;
        try {
            producer().send(new ProducerRecord<>(topic,
                    alarmId == null ? "unknown" : alarmId, payload)).get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            log.warn("Alert fan-out publish failed alarmId={} (Outbox remains pending): {}",
                    alarmId, ex.getMessage());
            return false;
        }
    }

    private volatile Boolean availableCache;
    private volatile long availableAt;

    /** Lightweight TCP probe used to avoid creating a producer during broker downtime. */
    public boolean isAvailable() {
        if (!enabled) return false;
        if (availableCache != null && System.currentTimeMillis() - availableAt < 5_000L) {
            return availableCache;
        }
        boolean ok = false;
        try {
            String hp = bootstrap.trim();
            int idx = hp.indexOf(':');
            String host = idx > 0 ? hp.substring(0, idx) : hp;
            int port = idx > 0 ? Integer.parseInt(hp.substring(idx + 1)) : 9092;
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 2_000);
                ok = true;
            }
        } catch (Exception ex) {
            log.warn("Kafka availability probe failed; Outbox remains pending: {}", ex.getMessage());
        }
        availableCache = ok;
        availableAt = System.currentTimeMillis();
        return ok;
    }
}
