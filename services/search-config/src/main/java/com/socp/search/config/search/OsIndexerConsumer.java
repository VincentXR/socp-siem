package com.socp.search.config.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.search.config.config.KafkaProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Value("${socp.os-indexer.enabled:true}")
    private boolean indexerEnabled;

    @Value("${socp.os-indexer.retry-backoff-ms:1000}")
    private long retryBackoffMs;

    private final OsEventWriter osWriter;
    private final KafkaProperties kafkaProperties;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> activeConsumer;
    private volatile KafkaProducer<String, String> dlqProducer;
    private Thread worker;

    public OsIndexerConsumer(OsEventWriter osWriter) {
        this(osWriter, new KafkaProperties());
    }

    @Autowired
    public OsIndexerConsumer(OsEventWriter osWriter, KafkaProperties kafkaProperties) {
        this.osWriter = osWriter;
        this.kafkaProperties = kafkaProperties;
    }

    @PostConstruct
    public void start() {
        if (!kafkaProperties.isEnabled() || !indexerEnabled) return;
        running = true;
        worker = Thread.ofPlatform().name("os-indexer").daemon(true).start(this::runLoop);
        log.info("OpenSearch indexer started bootstrap={} topic={}",
                kafkaProperties.getBootstrap(), kafkaProperties.getTopic());
    }

    private void runLoop() {
        while (running) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
                activeConsumer = consumer;
                consumer.subscribe(List.of(kafkaProperties.getTopic()));
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
            if (!processRecords(consumer, records)) backoffAfterFailure();
        }
    }

    /** Advances only successful partitions and rewinds failed partitions. */
    boolean processRecords(KafkaConsumer<String, String> consumer,
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
        return completed.size() == records.partitions().size();
    }

    private void backoffAfterFailure() {
        long delay = Math.max(100L, Math.min(10_000L, retryBackoffMs));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
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
            KafkaClientSupport.sendAndAwait(dlq(), kafkaProperties.getTopic() + "-dlq",
                    eventId, raw, Duration.ofSeconds(30));
            return true;
        } catch (RuntimeException failure) {
            log.warn("DLQ durable write failed eventId={}: {}", eventId, failure.getMessage());
            return false;
        }
    }

    private KafkaProducer<String, String> dlq() {
        KafkaProducer<String, String> current = dlqProducer;
        if (current != null) return current;
        synchronized (this) {
            if (dlqProducer == null) {
                dlqProducer = new KafkaProducer<>(KafkaClientSupport.reliableProducer(kafkaProperties.getBootstrap()));
            }
            return dlqProducer;
        }
    }

    private java.util.Properties consumerProperties() {
        return KafkaClientSupport.reliableConsumer(kafkaProperties.getBootstrap(),
                "socp-os-indexer", "earliest", 500);
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
