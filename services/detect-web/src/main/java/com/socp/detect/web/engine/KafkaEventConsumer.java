package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Consumes canonical events from Kafka and submits them to the detection engine.
 * The consumer uses manual commits, bounded in-process deduplication and a DLQ
 * path for malformed or failed records.
 */
@Component
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final int DEDUP_MAX = 100_000;
    private static final Set<String> DEDUP = Collections.synchronizedSet(new LinkedHashSet<>() {
        @Override
        public boolean add(String eventId) {
            if (size() >= DEDUP_MAX) {
                clear();
            }
            return super.add(eventId);
        }
    });

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private final DetectEngineService engine;
    private volatile org.apache.kafka.clients.producer.KafkaProducer<String, String> dlqProducer;
    private BiConsumer<String, String> dlqSink = this::publishDlq;

    public KafkaEventConsumer(DetectEngineService engine) {
        this.engine = engine;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            return;
        }
        Thread.ofPlatform().name("kafka-consumer").daemon(true).start(this::run);
        log.info("Kafka event consumer started bootstrap={} topic={}", bootstrap, topic);
    }

    private org.apache.kafka.clients.producer.KafkaProducer<String, String> dlq() {
        var producer = dlqProducer;
        if (producer == null) {
            synchronized (this) {
                if (dlqProducer == null) {
                    Properties props = new Properties();
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                            org.apache.kafka.common.serialization.StringSerializer.class.getName());
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                            org.apache.kafka.common.serialization.StringSerializer.class.getName());
                    props.put(org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all");
                    dlqProducer = new org.apache.kafka.clients.producer.KafkaProducer<>(props);
                }
                producer = dlqProducer;
            }
        }
        return producer;
    }

    private void toDlq(String eventId, String raw) {
        try {
            dlqSink.accept(eventId, raw);
        } catch (Exception ex) {
            log.warn("Failed to write event to DLQ eventId={}: {}", eventId, ex.getMessage());
        }
    }

    private void publishDlq(String eventId, String raw) {
        dlq().send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic + "-dlq",
                eventId == null ? "unknown" : eventId, raw));
    }

    /** Package-private hook used by focused tests and the polling loop. */
    void processRecord(String key, String raw) {
        String eventId = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = MAPPER.readValue(raw, Map.class);
            eventId = String.valueOf(event.getOrDefault("eventId", key));
            if (eventId != null && !"null".equals(eventId) && !DEDUP.add(eventId)) {
                return;
            }
            engine.ingest(toEvent(event));
        } catch (Exception ex) {
            log.warn("Failed to process Kafka event; sending to DLQ: {}", ex.getMessage());
            toDlq(eventId, raw);
        }
    }

    void setDlqSink(BiConsumer<String, String> sink) {
        this.dlqSink = sink == null ? this::publishDlq : sink;
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-detect");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (true) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (var record : records) {
                    String traceparent = null;
                    try {
                        var header = record.headers().lastHeader("traceparent");
                        if (header != null) {
                            traceparent = new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } catch (Exception ignored) {
                    }
                    String traceId = traceparent == null
                            ? null
                            : com.socp.platform.obs.TraceIdFilter.parseTraceId(traceparent);
                    if (traceId != null) {
                        org.slf4j.MDC.put("traceId", traceId);
                    }
                    try {
                        processRecord(record.key(), record.value());
                    } finally {
                        org.slf4j.MDC.remove("traceId");
                    }
                }
                if (!records.isEmpty()) {
                    try {
                        consumer.commitSync();
                    } catch (Exception ex) {
                        log.warn("Kafka commit failed; records will be retried: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Kafka consumer stopped: {}", ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static SecurityEvent toEvent(Map<String, Object> event) {
        Map<String, Object> rawFields = (Map<String, Object>) event.getOrDefault("fields", Map.of());
        Map<String, String> fields = new LinkedHashMap<>();
        for (var entry : rawFields.entrySet()) {
            fields.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        String message = event.get("msg") == null
                ? String.valueOf(event.getOrDefault("message", ""))
                : String.valueOf(event.get("msg"));
        if (event.containsKey("msg") && !fields.containsKey("msg")) {
            fields.put("msg", message);
        }
        Severity severity = Severity.INFO;
        try {
            severity = Severity.valueOf(String.valueOf(event.getOrDefault("severity", "INFO")).toUpperCase());
        } catch (IllegalArgumentException ignored) {
        }
        Instant timestamp = Instant.now();
        try {
            timestamp = Instant.parse(String.valueOf(event.getOrDefault("timestamp", timestamp)));
        } catch (Exception ignored) {
        }
        return new SecurityEvent(timestamp,
                String.valueOf(event.getOrDefault("source", "unknown")),
                String.valueOf(event.getOrDefault("host", "unknown")),
                message, fields, severity);
    }
}
