package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.store.DetectionStateStore;
import com.socp.detect.web.store.InMemoryDetectionStateStore;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Consumes canonical events from Kafka and submits them to the detection engine.
 * The consumer uses manual commits, a durable event-id claim/recovery journal
 * and a DLQ path for malformed or failed records.
 */
@Component
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private final DetectEngineService engine;
    private final DetectionStateStore stateStore;
    private volatile org.apache.kafka.clients.producer.KafkaProducer<String, String> dlqProducer;
    private BiConsumer<String, String> dlqSink = this::publishDlq;

    @org.springframework.beans.factory.annotation.Autowired
    public KafkaEventConsumer(DetectEngineService engine, DetectionStateStore stateStore) {
        this.engine = engine;
        this.stateStore = stateStore;
    }

    /** Unit-test/source compatibility constructor. */
    public KafkaEventConsumer(DetectEngineService engine) {
        this(engine, new InMemoryDetectionStateStore());
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
        processRecord(null, null, key, raw);
    }

    /** Process a Kafka record while retaining ownership metadata for recovery. */
    void processRecord(int partition, long offset, String key, String raw) {
        processRecord(Integer.valueOf(partition), Long.valueOf(offset), key, raw);
    }

    private void processRecord(Integer partition, Long offset, String key, String raw) {
        String eventId = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = MAPPER.readValue(raw, Map.class);
            eventId = String.valueOf(event.getOrDefault("eventId", key));
            SecurityEvent normalized = toEvent(event);
            String routingKey = com.socp.rule.partition.DetectionRoutingKey.forEvent(normalized);
            if (key != null && !key.equals(routingKey)) {
                log.warn("Kafka routing key mismatch eventId={} received={} expected={}; processing with expected ownership",
                        normalized.id(), key, routingKey);
            }
            if (!stateStore.recordIfNew(normalized, partition, offset, routingKey)) return;
            if (!engine.ingestFromKafka(normalized)) {
                stateStore.remove(normalized.id());
                throw new IllegalStateException("detection queue full");
            }
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
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    log.info("Detection partitions revoked: {}", partitions);
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    Set<Integer> assigned = partitions.stream()
                            .map(TopicPartition::partition)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    try {
                        engine.restoreForPartitions(assigned);
                        log.info("Detection state restored for partitions={}", assigned);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Detection partition state restore failed: " + ex.getMessage(), ex);
                    }
                }
            });
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
                        processRecord(record.partition(), record.offset(), record.key(), record.value());
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
        String eventId = String.valueOf(event.getOrDefault("eventId", "")).trim();
        if (eventId.isBlank() || "null".equalsIgnoreCase(eventId)) {
            eventId = UUID.randomUUID().toString();
        }
        return new SecurityEvent(eventId, timestamp,
                String.valueOf(event.getOrDefault("source", "unknown")),
                String.valueOf(event.getOrDefault("host", "unknown")),
                message, fields, severity);
    }
}
