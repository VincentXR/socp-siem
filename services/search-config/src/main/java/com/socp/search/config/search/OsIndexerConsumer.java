package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Replayable Kafka-to-OpenSearch indexer.
 *
 * <p>Offsets advance independently per partition only after every valid event
 * in that partition's poll batch receives a successful OpenSearch bulk item
 * acknowledgement, or an invalid event receives a broker-acknowledged DLQ
 * record. Failed partitions seek back to the first polled offset. OpenSearch
 * uses tenantId plus eventId as document _id, so replay is idempotent and
 * tenant-scoped.</p>
 */
@Component
public class OsIndexerConsumer {

    private static final Logger log = LoggerFactory.getLogger(OsIndexerConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    @Value("${socp.os-indexer.enabled:true}")
    private boolean indexerEnabled;

    private final OsEventWriter osWriter;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> activeConsumer;
    private volatile KafkaProducer<String, String> dlqProducer;
    private Thread worker;

    public OsIndexerConsumer(OsEventWriter osWriter) {
        this.osWriter = osWriter;
    }

    @PostConstruct
    public void start() {
        if (!enabled || !indexerEnabled) return;
        running = true;
        worker = Thread.ofPlatform().name("os-indexer").daemon(true).start(this::runLoop);
        log.info("OpenSearch indexer started bootstrap={} topic={}", bootstrap, topic);
    }

    private void runLoop() {
        while (running) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
                activeConsumer = consumer;
                consumer.subscribe(List.of(topic));
                consume(consumer);
            } catch (WakeupException wakeup) {
                if (running) log.warn("OpenSearch indexer consumer was unexpectedly woken");
            } catch (Exception failure) {
                if (running) {
                    log.warn("OpenSearch indexer consumer failed; restarting: {}", failure.getMessage());
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                activeConsumer = null;
            }
        }
    }

    private void consume(KafkaConsumer<String, String> consumer) {
        while (running) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (records.isEmpty()) continue;
            processRecords(consumer, records);
        }
    }

    /** Advances only successful partitions and rewinds failed partitions. */
    void processRecords(KafkaConsumer<String, String> consumer,
                        ConsumerRecords<String, String> records) {
        Map<TopicPartition, OffsetAndMetadata> completed = new HashMap<>();
        for (TopicPartition partition : records.partitions()) {
            List<ConsumerRecord<String, String>> partitionRecords = records.records(partition);
            if (processPartition(partitionRecords)) {
                long nextOffset = partitionRecords.getLast().offset() + 1;
                completed.put(partition, new OffsetAndMetadata(nextOffset));
            } else {
                consumer.seek(partition, partitionRecords.getFirst().offset());
            }
        }
        if (!completed.isEmpty()) consumer.commitSync(completed);
    }

    /** Package-visible correctness seam used by focused tests. */
    boolean processPartition(List<ConsumerRecord<String, String>> records) {
        String traceId = traceId(records.getFirst());
        if (traceId != null) org.slf4j.MDC.put("traceId", traceId);
        try {
            List<SearchEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> value = MAPPER.readValue(record.value(), Map.class);
                    events.add(toEvent(value, record.key()));
                } catch (Exception invalid) {
                    if (!sendToDlqAndAwait(record.key(), record.value())) {
                        log.warn("Invalid event DLQ write failed partition={} offset={}",
                                record.partition(), record.offset());
                        return false;
                    }
                }
            }
            return events.isEmpty() || osWriter.writeEventsAndAwait(events);
        } finally {
            org.slf4j.MDC.remove("traceId");
        }
    }

    private static String traceId(ConsumerRecord<String, String> record) {
        try {
            var header = record.headers().lastHeader("traceparent");
            if (header == null) return null;
            return com.socp.platform.obs.TraceIdFilter.parseTraceId(
                    new String(header.value(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean sendToDlqAndAwait(String eventId, String raw) {
        try {
            dlq().send(new ProducerRecord<>(topic + "-dlq",
                            eventId == null ? "unknown" : eventId, raw))
                    .get(30, TimeUnit.SECONDS);
            return true;
        } catch (Exception failure) {
            log.warn("DLQ durable write failed eventId={}: {}", eventId, failure.getMessage());
            return false;
        }
    }

    private KafkaProducer<String, String> dlq() {
        KafkaProducer<String, String> current = dlqProducer;
        if (current != null) return current;
        synchronized (this) {
            if (dlqProducer == null) {
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.ACKS_CONFIG, "all");
                props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
                props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
                props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
                dlqProducer = new KafkaProducer<>(props);
            }
            return dlqProducer;
        }
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "socp-os-indexer");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        return props;
    }

    private static SearchEvent toEvent(Map<String, Object> value, String fallbackId) {
        Instant timestamp = Instant.now();
        try {
            timestamp = Instant.parse(String.valueOf(value.getOrDefault("timestamp", timestamp)));
        } catch (Exception ignored) {
        }
        String eventId = String.valueOf(value.getOrDefault("eventId", fallbackId)).trim();
        if (eventId.isBlank() || "null".equalsIgnoreCase(eventId)) {
            throw new IllegalArgumentException("eventId is required for idempotent indexing");
        }
        return new SearchEvent(eventId, timestamp,
                String.valueOf(value.getOrDefault("source", "unknown")),
                String.valueOf(value.getOrDefault("host", "unknown")),
                String.valueOf(value.getOrDefault("severity", "INFO")),
                String.valueOf(value.getOrDefault("msg", "")),
                stringMap(value.get("fields")), stringMap(value.get("ecs")));
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), String.valueOf(item));
        });
        return Map.copyOf(result);
    }

    @PreDestroy
    void stop() {
        running = false;
        KafkaConsumer<String, String> consumer = activeConsumer;
        if (consumer != null) consumer.wakeup();
        if (worker != null) worker.interrupt();
        KafkaProducer<String, String> producer = dlqProducer;
        if (producer != null) producer.close(Duration.ofSeconds(2));
    }
}
