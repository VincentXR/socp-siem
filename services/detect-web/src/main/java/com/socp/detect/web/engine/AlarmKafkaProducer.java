package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Publishes the original alert stream consumed by detect-model. */
@Component
public class AlarmKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(AlarmKafkaProducer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.alarm-topic:socp-alarm-original}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private volatile KafkaProducer<String, String> producer;

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
                    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
                    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /**
     * Compatibility fire-and-forget API. New durable outbox code uses
     * {@link #sendAndAwait(Map, String)} so a failed send remains retryable.
     */
    public void send(Map<String, Object> alarm, String alertId) {
        Thread.startVirtualThread(() -> sendAndAwait(alarm, alertId));
    }

    /** Send with a bounded acknowledgement wait for the Detection outbox. */
    public boolean sendAndAwait(Map<String, Object> alarm, String alertId) {
        if (!enabled || alarm == null) return true;
        try {
            String value = MAPPER.writeValueAsString(alarm);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                    alertId == null ? "unknown" : alertId, value);
            String traceparent = com.socp.platform.obs.TraceIdFilter.buildTraceparent();
            if (traceparent != null) {
                record.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
            }
            producer().send(record).get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception ex) {
            log.warn("Original alert Kafka publish failed alertId={} (Detection outbox will retry): {}",
                    alertId, ex.getMessage());
            return false;
        }
    }
}
