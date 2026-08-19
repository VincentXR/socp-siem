package com.socp.detect.web.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.store.DetectionEventClaim;
import com.socp.detect.web.store.DetectionStateStore;
import com.socp.detect.web.store.InMemoryDetectionStateStore;
import com.socp.detect.web.store.PendingDetectionEvent;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Kafka consumer with partition-local serial processing and contiguous offset
 * commits. A committed offset means every earlier record in that partition has
 * completed its durable Detection result (or durable DLQ hand-off).
 */
@Component
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Duration RETRY_MAX = Duration.ofSeconds(30);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.group-id:socp-detect}")
    private String groupId;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private final DetectEngineService engine;
    private final DetectionStateStore stateStore;
    private final PartitionCompletionTracker completionTracker = new PartitionCompletionTracker();
    private final Map<Integer, ExecutorService> partitionLanes = new ConcurrentHashMap<>();
    private final BlockingQueue<RecordCompletion> completions = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile org.apache.kafka.clients.producer.KafkaProducer<String, String> dlqProducer;
    private BiConsumer<String, String> dlqSink = this::publishDlq;
    private volatile boolean customDlqSink;
    private Thread consumerThread;

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
        if (!enabled) return;
        consumerThread = Thread.ofPlatform().name("kafka-consumer").daemon(true).start(this::run);
        log.info("Kafka event consumer started bootstrap={} topic={} group={}", bootstrap, topic, groupId);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (consumerThread != null) consumerThread.interrupt();
        partitionLanes.values().forEach(ExecutorService::shutdownNow);
        partitionLanes.clear();
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

    /** Package-private hook used by focused tests. */
    void processRecord(String key, String raw) {
        try {
            processOne(null, null, key, raw);
        } catch (TerminalRecordException terminal) {
            try {
                publishDlqAndAwait(terminal.eventId, terminal.raw);
                stateStore.recordDeadLettered(terminal.eventId, terminal.raw, null, null,
                        terminal.getMessage());
            } catch (Exception ex) {
                log.warn("Unable to persist terminal record to DLQ: {}", ex.getMessage());
            }
        } catch (Exception ex) {
            log.warn("Detection record remains pending after transient failure: {}", ex.getMessage());
        }
    }

    /** Process one record once; the live consumer wraps this in retry logic. */
    void processRecord(int partition, long offset, String key, String raw) {
        try {
            processOne(partition, offset, key, raw);
        } catch (TerminalRecordException terminal) {
            try {
                publishDlqAndAwait(terminal.eventId, terminal.raw);
                stateStore.recordDeadLettered(terminal.eventId, terminal.raw, partition, offset,
                        terminal.getMessage());
            } catch (Exception ex) {
                log.warn("Unable to persist terminal record to DLQ: {}", ex.getMessage());
            }
        } catch (Exception ex) {
            // A direct/unit caller has no Kafka offset to acknowledge. Keep
            // transient failures visible and never turn them into a fake DLQ.
            log.warn("Detection record remains pending after transient failure: {}", ex.getMessage());
        }
    }

    void setDlqSink(BiConsumer<String, String> sink) {
        customDlqSink = sink != null;
        this.dlqSink = sink == null ? this::publishDlq : sink;
    }

    private void run() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 200);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1_800_000);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    log.info("Detection partitions revoked: {}", partitions);
                    for (TopicPartition partition : partitions) {
                        ExecutorService lane = partitionLanes.remove(partition.partition());
                        if (lane != null) lane.shutdownNow();
                        completionTracker.remove(partition.partition());
                    }
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    Set<Integer> assigned = partitions.stream()
                            .map(TopicPartition::partition)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    engine.rebuildForPartitions(assigned);
                    for (Integer partition : assigned) lane(partition);
                    replayPending(assigned);
                    log.info("Detection state restored for partitions={}", assigned);
                }
            });
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records) {
                    long epoch = completionTracker.register(record.partition(), record.offset());
                    lane(record.partition()).execute(() -> processWithRetry(record, epoch));
                }
                drainCompletions(consumer);
            }
        } catch (Exception ex) {
            if (running.get()) log.warn("Kafka consumer stopped: {}", ex.getMessage());
        } finally {
            partitionLanes.values().forEach(ExecutorService::shutdownNow);
            partitionLanes.clear();
        }
    }

    private ExecutorService lane(int partition) {
        return partitionLanes.computeIfAbsent(partition, ignored -> new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1_000),
                Thread.ofVirtual().name("detect-partition-" + partition + "-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy()));
    }

    private void replayPending(Set<Integer> partitions) {
        List<PendingDetectionEvent> pending = stateStore.pendingRecordsForPartitions(
                partitions, Duration.ofHours(24));
        for (PendingDetectionEvent row : pending) {
            if (row == null || row.event() == null || row.partition() == null) continue;
            lane(row.partition()).execute(() -> processPendingWithRetry(row));
        }
        if (!pending.isEmpty()) {
            log.info("Queued pending Detection journal rows for replay count={}", pending.size());
        }
    }

    private void processWithRetry(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
                                  long epoch) {
        long delay = 250;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                processOne(record.partition(), record.offset(), record.key(), record.value());
                completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                return;
            } catch (TerminalRecordException terminal) {
                try {
                    publishDlqAndAwait(terminal.eventId, terminal.raw);
                    stateStore.recordDeadLettered(terminal.eventId, terminal.raw,
                            record.partition(), record.offset(), terminal.getMessage());
                    completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                    return;
                } catch (Exception dlqFailure) {
                    log.warn("Terminal record DLQ unavailable; retrying eventId={}: {}",
                            terminal.eventId, dlqFailure.getMessage());
                }
            } catch (Exception transientFailure) {
                log.warn("Detection processing pending partition={} offset={} retry={} reason={}",
                        record.partition(), record.offset(), delay, transientFailure.getMessage());
                try {
                    engine.rebuildForPartitions(Set.of(record.partition()));
                } catch (Exception rebuildFailure) {
                    log.warn("Detection state rebuild deferred partition={}: {}",
                            record.partition(), rebuildFailure.getMessage());
                }
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = Math.min(RETRY_MAX.toMillis(), delay * 2);
        }
    }

    private void processPendingWithRetry(PendingDetectionEvent row) {
        long delay = 250;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                String routingKey = com.socp.rule.partition.DetectionRoutingKey.forEvent(row.event());
                processNormalized(row.partition(), row.offset(), routingKey, row.event());
                return;
            } catch (Exception failure) {
                log.warn("Pending Detection replay deferred partition={} offset={} retry={} reason={}",
                        row.partition(), row.offset(), delay, failure.getMessage());
                try {
                    engine.rebuildForPartitions(Set.of(row.partition()));
                } catch (Exception rebuildFailure) {
                    log.warn("Pending Detection state rebuild deferred partition={}: {}",
                            row.partition(), rebuildFailure.getMessage());
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delay = Math.min(RETRY_MAX.toMillis(), delay * 2);
            }
        }
    }

    private void drainCompletions(KafkaConsumer<String, String> consumer) {
        RecordCompletion completion;
        while ((completion = completions.poll()) != null) {
            completionTracker.complete(completion.partition(), completion.offset(), completion.epoch());
        }
        Map<TopicPartition, OffsetAndMetadata> ready = completionTracker.ready(topic);
        if (ready.isEmpty()) return;
        try {
            consumer.commitSync(ready);
            completionTracker.acknowledge(ready);
        } catch (Exception ex) {
            log.warn("Kafka contiguous offset commit failed; retrying: {}", ex.getMessage());
        }
    }

    private void processOne(Integer partition, Long offset, String key, String raw) {
        String eventId = null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = MAPPER.readValue(raw, Map.class);
            eventId = String.valueOf(event.getOrDefault("eventId", key));
            SecurityEvent normalized = toEvent(event);
            String routingKey = com.socp.rule.partition.DetectionRoutingKey.forEvent(normalized);
            if (key != null && !key.equals(routingKey)) {
                log.warn("Kafka routing key mismatch eventId={} received={} expected={}; using expected ownership",
                        normalized.id(), key, routingKey);
            }
            processNormalized(partition, offset, routingKey, normalized);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException malformed) {
            throw new TerminalRecordException(eventId, raw, malformed);
        }
    }

    private void processNormalized(Integer partition, Long offset, String routingKey,
                                   SecurityEvent normalized) {
        DetectionEventClaim claim = stateStore.claim(normalized, partition, offset, routingKey);
        if (claim == DetectionEventClaim.COMPLETED || claim == DetectionEventClaim.DEAD_LETTERED) return;

        CompletableFuture<Void> completion = engine.ingestFromKafkaAndAwait(normalized);
        if (completion == null) throw new IllegalStateException("detection completion signal is null");
        try {
            completion.get(10, TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("detection processing interrupted", interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("detection processing timeout", timeout);
        } catch (java.util.concurrent.ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw new IllegalStateException("durable detection result failed: " + cause.getMessage(), cause);
        }
        // EventAlertSink normally commits this in the same transaction as its
        // outbox rows. The idempotent call covers zero-alert/source-compatible
        // sinks and gives the consumer a clear terminal boundary.
        stateStore.markCompleted(normalized.id());
    }

    private void publishDlqAndAwait(String eventId, String raw) throws Exception {
        if (customDlqSink) {
            dlqSink.accept(eventId, raw);
            return;
        }
        dlq().send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic + "-dlq",
                eventId == null ? "unknown" : eventId, raw)).get(30, TimeUnit.SECONDS);
    }

    private void publishDlq(String eventId, String raw) {
        try {
            dlq().send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic + "-dlq",
                    eventId == null ? "unknown" : eventId, raw));
        } catch (Exception ex) {
            log.warn("Failed to write event to DLQ eventId={}: {}", eventId, ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static SecurityEvent toEvent(Map<String, Object> event) {
        Object rawFields = event.getOrDefault("fields", Map.of());
        if (!(rawFields instanceof Map<?, ?> inputFields)) {
            throw new ClassCastException("fields must be an object");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (var entry : inputFields.entrySet()) {
            fields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        String message = event.get("msg") == null
                ? String.valueOf(event.getOrDefault("message", ""))
                : String.valueOf(event.get("msg"));
        if (event.containsKey("msg") && !fields.containsKey("msg")) fields.put("msg", message);
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
        if (eventId.isBlank() || "null".equalsIgnoreCase(eventId)) eventId = UUID.randomUUID().toString();
        return new SecurityEvent(eventId, timestamp,
                String.valueOf(event.getOrDefault("source", "unknown")),
                String.valueOf(event.getOrDefault("host", "unknown")),
                message, fields, severity);
    }

    private record RecordCompletion(int partition, long offset, long epoch) {
    }

    private static final class TerminalRecordException extends RuntimeException {
        private final String eventId;
        private final String raw;

        private TerminalRecordException(String eventId, String raw, Throwable cause) {
            super("terminal record: " + cause.getMessage(), cause);
            this.eventId = eventId;
            this.raw = raw;
        }
    }
}
