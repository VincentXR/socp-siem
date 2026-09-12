package com.socp.detect.web.engine;

import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.PendingDetectionEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
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
    private static final Duration RETRY_MAX = Duration.ofSeconds(30);
    private static final Duration CONSUMER_RESTART_MAX = Duration.ofSeconds(30);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.group-id:socp-detect}")
    private String groupId;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    /**
     * Bounds repeated execution of a record whose failure is deterministic.
     * The terminal hand-off itself is still retried until the durable DLQ is
     * available, so an offset is never skipped merely because Kafka DLQ is down.
     */
    @Value("${socp.kafka.processing-max-attempts:8}")
    private int processingMaxAttempts;

    private final DetectEngineService engine;
    private final DetectionStateStore stateStore;
    private final DetectionPerformanceMetrics performanceMetrics;
    private final DetectionRecordProcessor recordProcessor;
    private final PartitionCompletionTracker completionTracker = new PartitionCompletionTracker();
    private static final int LANE_QUEUE_CAPACITY = 1_000;
    private static final int LANE_RESUME_THRESHOLD = LANE_QUEUE_CAPACITY / 2;
    private final Map<Integer, ThreadPoolExecutor> partitionLanes = new ConcurrentHashMap<>();
    /** Consumer-thread-owned tasks which could not yet enter their partition lane. */
    private final Map<TopicPartition, ArrayDeque<Runnable>> deferredWork = new ConcurrentHashMap<>();
    /** Partitions paused because their lane or deferred buffer is saturated. */
    private final Set<TopicPartition> pausedPartitions = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<RecordCompletion> completions = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile org.apache.kafka.clients.producer.KafkaProducer<String, String> dlqProducer;
    private BiConsumer<String, String> dlqSink = this::publishDlq;
    private volatile boolean customDlqSink;
    private Thread consumerThread;

    @org.springframework.beans.factory.annotation.Autowired
    public KafkaEventConsumer(DetectEngineService engine, DetectionStateStore stateStore,
                              DetectionPerformanceMetrics performanceMetrics) {
        this.engine = engine;
        this.stateStore = stateStore;
        this.performanceMetrics = performanceMetrics;
        this.recordProcessor = new DetectionRecordProcessor(engine, stateStore, performanceMetrics);
    }

    /** Unit-test/source compatibility constructor. */
    public KafkaEventConsumer(DetectEngineService engine, DetectionStateStore stateStore) {
        this.engine = engine;
        this.stateStore = stateStore;
        this.performanceMetrics = null;
        this.recordProcessor = new DetectionRecordProcessor(engine, stateStore, null);
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
        cleanupConsumerSession();
        var producer = dlqProducer;
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }

    private org.apache.kafka.clients.producer.KafkaProducer<String, String> dlq() {
        var producer = dlqProducer;
        if (producer == null) {
            synchronized (this) {
                if (dlqProducer == null) {
                    dlqProducer = new org.apache.kafka.clients.producer.KafkaProducer<>(
                            KafkaClientSupport.reliableProducer(bootstrap));
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
        } catch (DetectionRecordProcessor.MalformedDetectionRecordException terminal) {
            try {
                publishDlqAndAwait(terminal.eventId(), terminal.raw());
                stateStore.recordDeadLettered(terminal.eventId(), terminal.raw(), null, null,
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
        } catch (DetectionRecordProcessor.MalformedDetectionRecordException terminal) {
            try {
                publishDlqAndAwait(terminal.eventId(), terminal.raw());
                stateStore.recordDeadLettered(terminal.eventId(), terminal.raw(), partition, offset,
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

    /**
     * Supervises the actual consumer session. A broker/client exception must
     * never permanently kill the only polling thread while the JVM continues
     * to report itself alive. The session is closed, local lane state is
     * discarded, and a fresh consumer rejoins after bounded backoff.
     */
    private void run() {
        long restartDelay = 1_000;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                runConsumerSession();
                restartDelay = 1_000;
            } catch (Exception ex) {
                if (!running.get() || Thread.currentThread().isInterrupted()) break;
                log.error("Kafka consumer session failed; restarting in {}ms: {}",
                        restartDelay, ex.getMessage(), ex);
            } finally {
                cleanupConsumerSession();
            }

            if (!running.get()) break;
            try {
                Thread.sleep(restartDelay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            restartDelay = Math.min(CONSUMER_RESTART_MAX.toMillis(), restartDelay * 2);
        }
    }

    private void runConsumerSession() {
        var props = KafkaClientSupport.reliableConsumer(bootstrap, groupId, "earliest", 200);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 1_800_000);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override
                public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    log.info("Detection partitions revoked: {}", partitions);
                    for (TopicPartition partition : partitions) {
                        ThreadPoolExecutor lane = partitionLanes.remove(partition.partition());
                        if (lane != null) lane.shutdownNow();
                        deferredWork.remove(partition);
                        pausedPartitions.remove(partition);
                        completionTracker.remove(partition.partition());
                    }
                }

                @Override
                public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    Set<Integer> assigned = partitions.stream()
                            .map(TopicPartition::partition)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    com.socp.platform.tenant.context.TenantContext.runAsSystem(() -> {
                        engine.rebuildForPartitions(assigned);
                        for (Integer partition : assigned) lane(partition);
                        replayPending(assigned);
                    });
                    log.info("Detection state restored for partitions={}", assigned);
                }
            });
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                drainDeferred(consumer);
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records) {
                    long epoch = completionTracker.register(record.partition(), record.offset());
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    dispatchOrDefer(consumer, partition, () -> processWithRetry(record, epoch));
                }
                drainCompletions(consumer);
                drainDeferred(consumer);
            }
        }
    }

    private void cleanupConsumerSession() {
        partitionLanes.values().forEach(ThreadPoolExecutor::shutdownNow);
        partitionLanes.clear();
        deferredWork.clear();
        pausedPartitions.clear();
        completions.clear();
        for (Integer partition : completionTracker.partitions()) completionTracker.remove(partition);
    }

    private ThreadPoolExecutor lane(int partition) {
        return partitionLanes.computeIfAbsent(partition, ignored -> new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(LANE_QUEUE_CAPACITY),
                Thread.ofVirtual().name("detect-partition-" + partition + "-", 0).factory(),
                nonBlockingLaneBackpressure()));
    }

    /**
     * Reject immediately when a partition lane is saturated. The consumer
     * thread turns that rejection into a deferred task and pauses only the
     * affected partition, so other partitions continue to poll and commit.
     */
    static RejectedExecutionHandler nonBlockingLaneBackpressure() {
        return new ThreadPoolExecutor.AbortPolicy();
    }

    /**
     * Kept as a source-compatible alias for integrations which referenced the
     * old package-private test hook. It now has non-blocking semantics.
     */
    @Deprecated
    static RejectedExecutionHandler blockingLaneBackpressure() {
        return nonBlockingLaneBackpressure();
    }

    private boolean tryDispatch(int partition, Runnable task) {
        try {
            lane(partition).execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    private void dispatchOrDefer(KafkaConsumer<String, String> consumer,
                                 TopicPartition partition,
                                 Runnable task) {
        if (tryDispatch(partition.partition(), task)) return;
        deferredWork.computeIfAbsent(partition, ignored -> new ArrayDeque<>()).addLast(task);
        pausedPartitions.add(partition);
        if (consumer != null) consumer.pause(Set.of(partition));
    }

    /**
     * Move deferred work back into lanes without blocking the Kafka consumer
     * thread. A partition remains paused until its deferred buffer is empty and
     * its lane falls below the low-water mark.
     */
    private void drainDeferred(KafkaConsumer<String, String> consumer) {
        var iterator = deferredWork.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TopicPartition, ArrayDeque<Runnable>> entry = iterator.next();
            TopicPartition partition = entry.getKey();
            ArrayDeque<Runnable> deferred = entry.getValue();
            ThreadPoolExecutor lane = lane(partition.partition());

            while (!deferred.isEmpty() && tryDispatch(partition.partition(), deferred.peekFirst())) {
                deferred.removeFirst();
            }

            if (deferred.isEmpty() && lane.getQueue().size() <= LANE_RESUME_THRESHOLD) {
                deferredWork.remove(partition, deferred);
                pausedPartitions.remove(partition);
                consumer.resume(Set.of(partition));
            } else {
                pausedPartitions.add(partition);
                consumer.pause(Set.of(partition));
            }
        }

        if (!pausedPartitions.isEmpty()) consumer.pause(new HashSet<>(pausedPartitions));
    }

    private void replayPending(Set<Integer> partitions) {
        List<PendingDetectionEvent> pending = stateStore.pendingRecordsForPartitions(
                partitions, Duration.ofHours(24));
        for (PendingDetectionEvent row : pending) {
            if (row == null || row.event() == null || row.partition() == null) continue;
            dispatchOrDefer(null, new TopicPartition(topic, row.partition()),
                    () -> processPendingWithRetry(row));
        }
        if (!pending.isEmpty()) {
            log.info("Queued pending Detection journal rows for replay count={}", pending.size());
        }
    }

    private void processWithRetry(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
                                  long epoch) {
        long delay = 250;
        int attempts = 0;
        String traceparent = extractHeader(record, "traceparent");
        String traceId = extractTraceId(traceparent);

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (traceId != null) org.slf4j.MDC.put("traceId", traceId);
                if (traceparent != null) org.slf4j.MDC.put("traceparent", traceparent);
                processOne(record.partition(), record.offset(), record.key(), record.value());
                completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                return;
            } catch (DetectionRecordProcessor.MalformedDetectionRecordException terminal) {
                if (handoffToDlqUntilDurable(terminal.eventId(), terminal.raw(), record.partition(), record.offset(),
                        terminal.getMessage())) {
                    completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                }
                return;
            } catch (Exception failure) {
                attempts++;
                int limit = Math.max(1, processingMaxAttempts);
                log.warn("Detection processing failed partition={} offset={} attempt={}/{} reason={}",
                        record.partition(), record.offset(), attempts, limit, failure.getMessage());

                if (attempts >= limit) {
                    String eventId = record.key() == null || record.key().isBlank()
                            ? topic + "-" + record.partition() + "-" + record.offset() : record.key();
                    String reason = "processing attempts exhausted: " + failure.getMessage();
                    if (handoffToDlqUntilDurable(eventId, record.value(), record.partition(), record.offset(), reason)) {
                        completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                    }
                    return;
                }

                // The engine state is instance-wide. A partition-only rebuild
                // would discard windows belonging to sibling partitions, but
                // rebuilding all owned partitions on every retry causes a
                // retry storm. Recover once, then retry the durable record.
                if (attempts == 1) {
                    try {
                        rebuildOwnedState(record.partition());
                    } catch (Exception rebuildFailure) {
                        log.warn("Detection state rebuild deferred partition={}: {}",
                                record.partition(), rebuildFailure.getMessage());
                    }
                }
            } finally {
                if (traceId != null) org.slf4j.MDC.remove("traceId");
                if (traceparent != null) org.slf4j.MDC.remove("traceparent");
                com.socp.platform.tenant.context.TenantContext.clear();
            }
            if (!sleepRetry(delay)) return;
            delay = Math.min(RETRY_MAX.toMillis(), delay * 2);
        }
    }

    private static String extractHeader(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record, String name) {
        if (record == null || record.headers() == null) return null;
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) return null;
        return new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String extractTraceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) return null;
        String[] parts = traceparent.trim().split("-");
        return parts.length >= 2 ? parts[1] : traceparent;
    }

    private void processPendingWithRetry(PendingDetectionEvent row) {
        long delay = 250;
        int attempts = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                String routingKey = com.socp.rule.partition.DetectionRoutingKey.forEvent(row.event());
                com.socp.platform.tenant.context.TenantContext.runWith(
                        row.event().requireTenantId(),
                        () -> recordProcessor.processNormalized(row.partition(), row.offset(), routingKey, row.event()));
                return;
            } catch (Exception failure) {
                attempts++;
                int limit = Math.max(1, processingMaxAttempts);
                log.warn("Pending Detection replay failed partition={} offset={} attempt={}/{} reason={}",
                        row.partition(), row.offset(), attempts, limit, failure.getMessage());

                if (attempts >= limit) {
                    handoffToDlqUntilDurable(row.event().id(), row.event().raw(), row.partition(), row.offset(),
                            "pending replay attempts exhausted: " + failure.getMessage());
                    return;
                }

                if (attempts == 1) {
                    try {
                        com.socp.platform.tenant.context.TenantContext.runAsSystem(
                                () -> rebuildOwnedState(row.partition()));
                    } catch (Exception rebuildFailure) {
                        log.warn("Pending Detection state rebuild deferred partition={}: {}",
                                row.partition(), rebuildFailure.getMessage());
                    }
                }
                if (!sleepRetry(delay)) return;
                delay = Math.min(RETRY_MAX.toMillis(), delay * 2);
            }
        }
    }

    /**
     * Once processing is deemed terminal, retry only the durable DLQ hand-off.
     * This prevents repeated business side effects while still preserving the
     * contiguous-offset guarantee when the DLQ broker is temporarily down.
     */
    private boolean handoffToDlqUntilDurable(String eventId, String raw,
                                             Integer partition, Long offset, String reason) {
        long delay = 1_000;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                publishDlqAndAwait(eventId, raw);
                stateStore.recordDeadLettered(eventId, raw, partition, offset, reason);
                return true;
            } catch (Exception dlqFailure) {
                log.error("Detection DLQ hand-off unavailable partition={} offset={}; retrying in {}ms: {}",
                        partition, offset, delay, dlqFailure.getMessage());
                if (!sleepRetry(delay)) return false;
                delay = Math.min(RETRY_MAX.toMillis(), delay * 2);
            }
        }
        return false;
    }

    private static boolean sleepRetry(long delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
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
        recordProcessor.process(partition, offset, key, raw);
    }

    private void rebuildOwnedState(int failedPartition) {
        Set<Integer> owned = engine.assignedPartitions();
        // RuleEngine is instance-wide, not partition-local. Replacing it from
        // only the failed partition would silently discard hot windows for the
        // other partitions still owned by this consumer.
        engine.rebuildForPartitions(owned == null || owned.isEmpty()
                ? Set.of(failedPartition) : owned);
    }

    private void publishDlqAndAwait(String eventId, String raw) throws Exception {
        if (customDlqSink) {
            dlqSink.accept(eventId, raw);
            return;
        }
        KafkaClientSupport.sendAndAwait(dlq(), topic + "-dlq", eventId, raw, Duration.ofSeconds(30));
    }

    private void publishDlq(String eventId, String raw) {
        try {
            dlq().send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic + "-dlq",
                    eventId == null ? "unknown" : eventId, raw));
        } catch (Exception ex) {
            log.warn("Failed to write event to DLQ eventId={}: {}", eventId, ex.getMessage());
        }
    }

    private record RecordCompletion(int partition, long offset, long epoch) {
    }
}
