package com.socp.detect.web.engine;

import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.client.kafka.KafkaTrace;
import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.detect.web.persistence.store.InMemoryDetectionStateStore;
import com.socp.detect.web.persistence.store.PendingDetectionEvent;
import com.socp.rule.model.SecurityEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Kafka consumer with partition-local serial processing and contiguous offset
 * commits. A committed offset means every earlier record in that partition has
 * completed its durable Detection result (or durable DLQ hand-off).
 */
@Component
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);
    private static final Duration RETRY_MAX = Duration.ofSeconds(30);
    private static final Duration CONSUMER_RESTART_MAX = Duration.ofSeconds(30);

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.detect.input-topic:${socp.kafka.topic:socp-events}}")
    private String topic;

    @Value("${socp.kafka.group-id:socp-detect}")
    private String groupId;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    /**
     * Per-round retry budget. Spending the budget never converts a parsed event
     * into poison and never forgets the record: the partition remains blocked,
     * the lane keeps retry responsibility, and the consumer thread keeps polling
     * the other partitions.
     */
    @Value("${socp.kafka.processing-max-attempts:8}")
    private int processingMaxAttempts;

    @Value("${socp.kafka.processing-retry-initial-delay-ms:250}")
    private long processingRetryInitialDelayMs = 250L;

    private final DetectEngineService engine;
    private final DetectionStateStore stateStore;
    private final DetectionPerformanceMetrics performanceMetrics;
    private final DetectionRecordProcessor recordProcessor;
    private final PartitionCompletionTracker completionTracker = new PartitionCompletionTracker();
    private static final int LANE_QUEUE_CAPACITY = 1_000;
    private static final int LANE_RESUME_THRESHOLD = LANE_QUEUE_CAPACITY / 2;
    private final Map<Integer, ThreadPoolExecutor> partitionLanes = new ConcurrentHashMap<>();
    /** Consumer-thread-owned tasks which could not yet enter their partition lane. */
    private final Map<TopicPartition, ArrayDeque<PendingWork>> deferredWork = new ConcurrentHashMap<>();
    /** In-flight, lane-queued and deferred bytes for each Kafka partition. */
    private final Map<TopicPartition, AtomicLong> pendingBytes = new ConcurrentHashMap<>();
    /** Backpressure and failure retries are independent pause reasons. */
    private final Set<TopicPartition> backpressureBlockedPartitions = ConcurrentHashMap.newKeySet();
    private final Set<TopicPartition> retryBlockedPartitions = ConcurrentHashMap.newKeySet();
    private final Map<TopicPartition, Long> retryBlockedSince = new ConcurrentHashMap<>();
    private final Map<TopicPartition, String> retryBlockedCategory = new ConcurrentHashMap<>();
    /** Consumer-thread view of the pauses this component has applied. */
    private final Set<TopicPartition> appliedPausedPartitions = ConcurrentHashMap.newKeySet();
    /** Ownership loss requests a clean consumer-session restart/rejoin. */
    private final AtomicBoolean sessionRestartRequested = new AtomicBoolean();
    private final BlockingQueue<RecordCompletion> completions = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Bounds the dead-letter hand-off so an unreachable broker cannot wedge a
     * lane. Both are operator-tunable so a longer broker outage can be waited
     * out without a code change.
     */
    @Value("${socp.kafka.dlq-handoff-max-attempts:5}")
    private int dlqHandoffMaxAttempts = 5;

    @Value("${socp.kafka.dlq-handoff-retry-delay-ms:1000}")
    private long dlqHandoffRetryDelayMs = 1_000;
    private volatile org.apache.kafka.clients.producer.KafkaProducer<String, String> dlqProducer;
    /** Set by {@link #setDlqSink}; null means the Kafka DLQ path is used. */
    private BiConsumer<String, String> dlqSink;
    private volatile boolean customDlqSink;
    private Thread consumerThread;

    @Value("${socp.detect.backpressure.partition-max-bytes:16777216}")
    private long partitionMaxPendingBytes = 16L * 1024 * 1024;

    @Value("${socp.detect.state.retention}")
    private Duration replayWindow;

    @Value("${socp.detect.state.replay-pending-max:100}")
    private int pendingReplayMax = 100;

    /** Optional: the compatibility constructors and focused tests are wiring-free. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.micrometer.core.instrument.MeterRegistry metrics;

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

    @org.springframework.beans.factory.annotation.Autowired
    void configureRouting(com.socp.detect.web.routing.DetectionRoutingRuntime runtime) {
        recordProcessor.setRoutedInput(runtime.routedDetection());
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        registerMetrics();
        consumerThread = Thread.ofPlatform().name("kafka-consumer").daemon(true).start(this::run);
        log.info("Kafka event consumer started bootstrap={} topic={} group={}", bootstrap, topic, groupId);
    }

    /**
     * A pinned partition commit and an abandoned hand-off are otherwise only
     * visible in logs while every health probe stays green, so both become
     * counters and the commit gap becomes a gauge.
     */
    private void registerMetrics() {
        if (metrics == null) return;
        metrics.gauge("socp.detection.offset.pinned", completionTracker,
                ignored -> {
                    long pinned = 0L;
                    for (Integer partition : completionTracker.partitions()) {
                        pinned += completionTracker.pendingOffsets(partition);
                    }
                    return pinned;
                });
        metrics.gauge("socp.detection.partition.retry.blocked", retryBlockedPartitions,
                Set::size);
        metrics.gauge("socp.detection.partition.retry.oldest.seconds", retryBlockedSince,
                ignored -> oldestRetryBlockedSeconds());
    }

    private void count(String name, String outcome) {
        if (metrics == null) return;
        metrics.counter(name, "outcome", outcome).increment();
    }

    private void countFailure(DetectionRecordProcessor.RetryableDetectionFailure failure) {
        if (metrics == null || failure == null) return;
        metrics.counter("socp.detection.processing.failure",
                "category", failure.category().metricTag(),
                "stage", failure.stage().metricTag()).increment();
    }

    private double oldestRetryBlockedSeconds() {
        long now = System.currentTimeMillis();
        long oldest = Long.MAX_VALUE;
        for (Long since : retryBlockedSince.values()) {
            if (since != null) oldest = Math.min(oldest, since);
        }
        return oldest == Long.MAX_VALUE ? 0.0 : Math.max(0L, now - oldest) / 1000.0;
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
            handoffToDlqUntilDurable(new DlqHandoff(terminal.eventId(), null, key, terminal.raw(),
                    null, null, terminal.getMessage(), null));
        } catch (Exception ex) {
            log.warn("Detection record remains pending after transient failure: {}", ex.getMessage());
        }
    }

    /** Process one record once; the live consumer wraps this in retry logic. */
    void processRecord(int partition, long offset, String key, String raw) {
        try {
            processOne(partition, offset, key, raw);
        } catch (DetectionRecordProcessor.MalformedDetectionRecordException terminal) {
            handoffToDlqUntilDurable(new DlqHandoff(terminal.eventId(), null, key, terminal.raw(),
                    partition, offset, terminal.getMessage(), null));
        } catch (Exception ex) {
            // A direct/unit caller has no Kafka offset to acknowledge. Keep
            // transient failures visible and never turn them into a fake DLQ.
            log.warn("Detection record remains pending after transient failure: {}", ex.getMessage());
        }
    }

    void setDlqSink(BiConsumer<String, String> sink) {
        customDlqSink = sink != null;
        this.dlqSink = sink;
    }

    /**
     * Supervise the Kafka consumer session. A broker/client failure must not
     * permanently kill the only polling thread while the JVM stays healthy.
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
            if (!sleepRetry(restartDelay)) break;
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
                        if (lane != null) releaseDropped(lane.shutdownNow());
                        ArrayDeque<PendingWork> deferred = deferredWork.remove(partition);
                        if (deferred != null) releaseDeferred(deferred);
                        pendingBytes.remove(partition);
                        backpressureBlockedPartitions.remove(partition);
                        clearRetryBlocked(partition, false);
                        appliedPausedPartitions.remove(partition);
                        completionTracker.remove(partition.partition());
                    }
                    engine.releaseForPartitions(partitions.stream()
                            .map(TopicPartition::partition)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
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
                if (sessionRestartRequested.getAndSet(false)) {
                    throw new IllegalStateException("detection ownership epoch changed; rejoining consumer group");
                }
                drainDeferred(consumer);
                applyPauseState(consumer);
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records) {
                    long epoch = completionTracker.register(record.partition(), record.offset());
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    dispatchOrDefer(consumer, partition, () -> processWithRetry(record, epoch),
                            estimateRecordBytes(record));
                }
                drainCompletions(consumer);
                drainDeferred(consumer);
                applyPauseState(consumer);
            }
        }
    }

    /** Clear all session-local work and relinquish fencing leases before rejoining. */
    private void cleanupConsumerSession() {
        Set<Integer> owned = engine.assignedPartitions();
        partitionLanes.values().forEach(lane -> releaseDropped(lane.shutdownNow()));
        partitionLanes.clear();
        deferredWork.values().forEach(KafkaEventConsumer::releaseDeferred);
        deferredWork.clear();
        pendingBytes.clear();
        backpressureBlockedPartitions.clear();
        retryBlockedPartitions.clear();
        retryBlockedSince.clear();
        retryBlockedCategory.clear();
        appliedPausedPartitions.clear();
        sessionRestartRequested.set(false);
        completions.clear();
        for (Integer partition : completionTracker.partitions()) completionTracker.remove(partition);
        if (owned != null && !owned.isEmpty()) {
            try {
                engine.releaseForPartitions(Set.copyOf(owned));
            } catch (Exception releaseFailure) {
                log.warn("Detection state lease release failed during consumer cleanup: {}",
                        releaseFailure.getMessage());
            }
        }
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
        dispatchOrDefer(consumer, partition, task, 1L);
    }

    private void dispatchOrDefer(KafkaConsumer<String, String> consumer,
                                 TopicPartition partition,
                                 Runnable task,
                                 long estimatedBytes) {
        long bytes = Math.max(1L, estimatedBytes);
        AtomicLong partitionBytes = pendingBytes.computeIfAbsent(partition,
                ignored -> new AtomicLong());
        boolean overBudget = reserveBytes(partitionBytes, bytes);
        PendingWork work = new PendingWork(task, bytes, partitionBytes);
        if (!overBudget && tryDispatch(partition.partition(), work)) return;
        // A poll may already contain more records than the byte budget. Keep
        // those records losslessly in the bounded deferred batch, but stop
        // polling the partition so the overshoot cannot continue indefinitely.
        deferredWork.computeIfAbsent(partition, ignored -> new ArrayDeque<>()).addLast(work);
        backpressureBlockedPartitions.add(partition);
    }

    private boolean reserveBytes(AtomicLong current, long bytes) {
        long limit = Math.max(1L, partitionMaxPendingBytes);
        for (;;) {
            long before = current.get();
            long after;
            try {
                after = Math.addExact(before, bytes);
            } catch (ArithmeticException overflow) {
                after = Long.MAX_VALUE;
            }
            // Allow one oversized record through when the partition is idle;
            // otherwise it could never make progress under a too-small limit.
            if (after > limit && before > 0L) {
                current.addAndGet(bytes);
                return true;
            }
            if (current.compareAndSet(before, after)) return after > limit;
        }
    }

    /**
     * Move deferred work back into lanes without blocking the Kafka consumer
     * thread. A partition remains paused until its deferred buffer is empty and
     * its lane falls below the low-water mark.
     */
    private void drainDeferred(KafkaConsumer<String, String> consumer) {
        var iterator = deferredWork.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TopicPartition, ArrayDeque<PendingWork>> entry = iterator.next();
            TopicPartition partition = entry.getKey();
            ArrayDeque<PendingWork> deferred = entry.getValue();
            ThreadPoolExecutor lane = lane(partition.partition());

            while (!deferred.isEmpty() && tryDispatch(partition.partition(), deferred.peekFirst())) {
                deferred.removeFirst();
            }

            if (deferred.isEmpty() && lane.getQueue().size() <= LANE_RESUME_THRESHOLD) {
                deferredWork.remove(partition, deferred);
                backpressureBlockedPartitions.remove(partition);
            } else {
                backpressureBlockedPartitions.add(partition);
            }
        }
    }

    /**
     * KafkaConsumer is thread-confined. Worker lanes only mutate desired pause
     * reasons; the polling thread is the sole caller of pause/resume.
     */
    private void applyPauseState(KafkaConsumer<String, String> consumer) {
        if (consumer == null) return;
        Set<TopicPartition> assigned = consumer.assignment();
        Set<TopicPartition> desired = new HashSet<>(backpressureBlockedPartitions);
        desired.addAll(retryBlockedPartitions);
        desired.retainAll(assigned);

        Set<TopicPartition> toPause = new HashSet<>(desired);
        toPause.removeAll(appliedPausedPartitions);
        if (!toPause.isEmpty()) consumer.pause(toPause);

        Set<TopicPartition> toResume = new HashSet<>(appliedPausedPartitions);
        toResume.removeAll(desired);
        toResume.retainAll(assigned);
        if (!toResume.isEmpty()) consumer.resume(toResume);

        appliedPausedPartitions.clear();
        appliedPausedPartitions.addAll(desired);
    }

    private void markRetryBlocked(TopicPartition partition,
                                  DetectionRecordProcessor.RetryableDetectionFailure failure) {
        if (partition == null || failure == null) return;
        retryBlockedPartitions.add(partition);
        retryBlockedSince.putIfAbsent(partition, System.currentTimeMillis());
        retryBlockedCategory.put(partition, failure.category().metricTag());
        countFailure(failure);
    }

    private void clearRetryBlocked(TopicPartition partition, boolean recovered) {
        if (partition == null) return;
        String category = retryBlockedCategory.remove(partition);
        boolean wasBlocked = retryBlockedPartitions.remove(partition);
        retryBlockedSince.remove(partition);
        if (recovered && wasBlocked) {
            count("socp.detection.processing.recovered",
                    category == null ? "unknown" : category);
        }
    }

    private void replayPending(Set<Integer> partitions) {
        int cap = pendingReplayMax <= 0 ? 100 : pendingReplayMax;
        java.util.concurrent.atomic.AtomicInteger queued =
                new java.util.concurrent.atomic.AtomicInteger();
        stateStore.replayPendingPages(partitions, configuredReplayWindow(), cap, batch -> {
            for (PendingDetectionEvent row : batch) {
                if (row == null || row.event() == null || row.partition() == null) continue;
                dispatchOrDefer(null, new TopicPartition(configuredTopic(), row.partition()),
                        () -> processPendingWithRetry(row), estimateEventBytes(row.event()));
                queued.incrementAndGet();
            }
        });
        if (queued.get() > 0) {
            log.info("Queued bounded PENDING journal prefetch count={}; any remaining rows "
                    + "stay behind uncommitted Kafka offsets and will be redelivered", queued.get());
        }
    }

    /** Compatibility constructors do not have Spring property injection. */
    private Duration configuredReplayWindow() {
        return replayWindow == null ? Duration.ZERO : replayWindow;
    }

    private String configuredTopic() {
        return topic == null || topic.isBlank() ? "socp-events" : topic;
    }

    /**
     * Package-private so the retry, dead-letter and completion decisions can be
     * driven with a real record; {@link #processRecord(String, String)} bypasses
     * this path because it carries no Kafka headers and therefore no trace
     * context.
     */
    void processWithRetry(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
                          long epoch) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        long delay = Math.max(1L, processingRetryInitialDelayMs);
        int attemptsThisRound = 0;
        DetectionRecordProcessor.InFlightDetectionTimeout inFlight = null;
        DetectionRecordProcessor.FinalizationPendingFailure finalization = null;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                DetectionRecordProcessor.InFlightDetectionTimeout timedOut = inFlight;
                DetectionRecordProcessor.FinalizationPendingFailure pendingFinalization = finalization;
                KafkaTrace.runConsumed("detect " + record.topic() + " receive", record.headers(), () -> {
                    if (timedOut != null) {
                        recordProcessor.resumeTimedOut(timedOut, Math.min(RETRY_MAX.toMillis(), 30_000L));
                    } else if (pendingFinalization != null) {
                        recordProcessor.resumeFinalization(pendingFinalization);
                    } else {
                        processOne(record.topic(), record.partition(), record.offset(),
                                record.key(), record.value());
                    }
                });
                clearRetryBlocked(partition, true);
                completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                return;
            } catch (DetectionRecordProcessor.MalformedDetectionRecordException terminal) {
                // Parsing/shape validation is the only poison-record boundary.
                if (handoffToDlqUntilDurable(new DlqHandoff(terminal.eventId(), terminal.tenantId(),
                        record.key(), terminal.raw(), record.partition(), record.offset(),
                        terminal.getMessage(), record.headers()))) {
                    clearRetryBlocked(partition, true);
                    completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                }
                return;
            } catch (DetectionRecordProcessor.InFlightDetectionTimeout timedOut) {
                inFlight = timedOut;
                finalization = null;
                attemptsThisRound = recordRetryFailure(partition, record, timedOut,
                        attemptsThisRound, delay);
                if (ownershipLost(timedOut)) return;
            } catch (DetectionRecordProcessor.FinalizationPendingFailure pending) {
                finalization = pending;
                inFlight = null;
                attemptsThisRound = recordRetryFailure(partition, record, pending,
                        attemptsThisRound, delay);
                if (ownershipLost(pending)) return;
            } catch (DetectionRecordProcessor.RetryableDetectionFailure retryable) {
                // If an original timed-out future has now settled exceptionally,
                // it is safe to evaluate again; otherwise InFlightDetectionTimeout
                // above retains the exact future and prevents concurrent retries.
                inFlight = null;
                finalization = null;
                attemptsThisRound = recordRetryFailure(partition, record, retryable,
                        attemptsThisRound, delay);
                if (ownershipLost(retryable)) return;
            } catch (Exception unknown) {
                inFlight = null;
                finalization = null;
                DetectionRecordProcessor.RetryableDetectionFailure retryable =
                        retryableUnknown(record, unknown);
                attemptsThisRound = recordRetryFailure(partition, record, retryable,
                        attemptsThisRound, delay);
                if (ownershipLost(retryable)) return;
            } finally {
                com.socp.platform.tenant.context.TenantContext.clear();
            }

            if (!sleepRetry(jitteredDelay(delay))) return;
            delay = Math.min(RETRY_MAX.toMillis(), Math.max(1L, delay * 2));
        }
    }

    private int recordRetryFailure(
            TopicPartition partition,
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
            DetectionRecordProcessor.RetryableDetectionFailure failure,
            int attemptsThisRound,
            long delay) {
        int attempts = attemptsThisRound + 1;
        int limit = Math.max(1, processingMaxAttempts);
        markRetryBlocked(partition, failure);
        log.warn("Detection processing retry partition={} offset={} attempt={}/{} category={} "
                        + "stage={} nextDelayMs={} reason={}",
                record.partition(), record.offset(), attempts, limit,
                failure.category().metricTag(), failure.stage().metricTag(),
                jitterCeiling(delay), failure.getMessage());
        if (failure.category() == DetectionRecordProcessor.FailureCategory.OWNERSHIP_LOST) {
            sessionRestartRequested.set(true);
            count("socp.detection.processing.withheld", "ownership_lost");
            return attempts;
        }
        if (attempts >= limit) {
            count("socp.detection.processing.retry.round", failure.category().metricTag());
            return 0;
        }
        return attempts;
    }

    private boolean ownershipLost(DetectionRecordProcessor.RetryableDetectionFailure failure) {
        return failure != null
                && failure.category() == DetectionRecordProcessor.FailureCategory.OWNERSHIP_LOST;
    }

    private DetectionRecordProcessor.RetryableDetectionFailure retryableUnknown(
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
            Throwable failure) {
        return new DetectionRecordProcessor.RetryableDetectionFailure(
                "kafka-offset:" + record.partition() + ":" + record.offset(),
                null,
                DetectionRecordProcessor.FailureStage.EVALUATION,
                DetectionRecordProcessor.classifyFailure(failure),
                "unclassified detection execution failure remains retryable: "
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                failure);
    }

    /** Package-private hook used by focused tests and bounded PENDING prefetch. */
    void processPendingWithRetry(PendingDetectionEvent row) {
        TopicPartition partition = new TopicPartition(configuredTopic(), row.partition());
        long delay = Math.max(1L, processingRetryInitialDelayMs);
        int attemptsThisRound = 0;
        DetectionRecordProcessor.InFlightDetectionTimeout inFlight = null;
        DetectionRecordProcessor.FinalizationPendingFailure finalization = null;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (inFlight != null) {
                    recordProcessor.resumeTimedOut(inFlight, Math.min(RETRY_MAX.toMillis(), 30_000L));
                } else if (finalization != null) {
                    recordProcessor.resumeFinalization(finalization);
                } else {
                    com.socp.platform.tenant.context.TenantContext.runWith(
                            row.event().requireTenantId(),
                            () -> recordProcessor.processNormalized(row.partition(), row.offset(),
                                    com.socp.rule.partition.DetectionRoutingKey.forEvent(row.event()),
                                    row.event()));
                }
                clearRetryBlocked(partition, true);
                return;
            } catch (DetectionRecordProcessor.InFlightDetectionTimeout timedOut) {
                inFlight = timedOut;
                finalization = null;
                attemptsThisRound = recordPendingRetryFailure(
                        partition, row, timedOut, attemptsThisRound, delay);
                if (ownershipLost(timedOut)) return;
            } catch (DetectionRecordProcessor.FinalizationPendingFailure pending) {
                finalization = pending;
                inFlight = null;
                attemptsThisRound = recordPendingRetryFailure(
                        partition, row, pending, attemptsThisRound, delay);
                if (ownershipLost(pending)) return;
            } catch (DetectionRecordProcessor.RetryableDetectionFailure retryable) {
                inFlight = null;
                finalization = null;
                attemptsThisRound = recordPendingRetryFailure(
                        partition, row, retryable, attemptsThisRound, delay);
                if (ownershipLost(retryable)) return;
            } catch (Exception unknown) {
                inFlight = null;
                finalization = null;
                DetectionRecordProcessor.RetryableDetectionFailure retryable =
                        new DetectionRecordProcessor.RetryableDetectionFailure(
                                row.event().id(), row.event().requireTenantId(),
                                DetectionRecordProcessor.FailureStage.EVALUATION,
                                DetectionRecordProcessor.classifyFailure(unknown),
                                "unclassified PENDING replay failure remains retryable: "
                                        + unknown.getClass().getSimpleName() + ": " + unknown.getMessage(),
                                unknown);
                attemptsThisRound = recordPendingRetryFailure(
                        partition, row, retryable, attemptsThisRound, delay);
                if (ownershipLost(retryable)) return;
            } finally {
                com.socp.platform.tenant.context.TenantContext.clear();
            }

            if (!sleepRetry(jitteredDelay(delay))) return;
            delay = Math.min(RETRY_MAX.toMillis(), Math.max(1L, delay * 2));
        }
    }

    private int recordPendingRetryFailure(
            TopicPartition partition,
            PendingDetectionEvent row,
            DetectionRecordProcessor.RetryableDetectionFailure failure,
            int attemptsThisRound,
            long delay) {
        int attempts = attemptsThisRound + 1;
        int limit = Math.max(1, processingMaxAttempts);
        markRetryBlocked(partition, failure);
        log.warn("Pending Detection retry partition={} offset={} attempt={}/{} category={} "
                        + "stage={} nextDelayMs={} reason={}",
                row.partition(), row.offset(), attempts, limit,
                failure.category().metricTag(), failure.stage().metricTag(),
                jitterCeiling(delay), failure.getMessage());
        if (failure.category() == DetectionRecordProcessor.FailureCategory.OWNERSHIP_LOST) {
            sessionRestartRequested.set(true);
            return attempts;
        }
        if (attempts >= limit) {
            count("socp.detection.processing.retry.round", failure.category().metricTag());
            return 0;
        }
        return attempts;
    }

    /**
     * Complete a poison-record hand-off without forgetting it in the current
     * consumer session. The per-round attempt budget only bounds burst pressure;
     * a Kafka-backed record retains responsibility and retries with jittered
     * backoff until both the DLQ publish and applicable terminal journal write
     * are durable, or until revoke/shutdown interrupts its lane.
     */
    private boolean handoffToDlqUntilDurable(DlqHandoff handoff) {
        long delay = Math.max(1L, dlqHandoffRetryDelayMs);
        int limit = Math.max(1, dlqHandoffMaxAttempts);
        int attemptsThisRound = 0;
        boolean dlqPublished = false;
        TopicPartition partition = handoff.partition() == null
                ? null : new TopicPartition(configuredTopic(), handoff.partition());

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!dlqPublished) {
                    publishDlqAndAwait(handoff);
                    dlqPublished = true;
                }
                recordTerminalJournalRow(handoff);
                clearRetryBlocked(partition, true);
                count("socp.detection.dlq.handoff", "committed");
                return true;
            } catch (Exception dlqFailure) {
                attemptsThisRound++;
                DetectionRecordProcessor.RetryableDetectionFailure retryable =
                        new DetectionRecordProcessor.RetryableDetectionFailure(
                                handoff.eventId(), handoff.tenant(),
                                DetectionRecordProcessor.FailureStage.MARK_COMPLETED,
                                DetectionRecordProcessor.classifyFailure(dlqFailure),
                                "dead-letter hand-off is not durable yet: "
                                        + dlqFailure.getClass().getSimpleName() + ": "
                                        + dlqFailure.getMessage(),
                                dlqFailure);
                markRetryBlocked(partition, retryable);
                log.error("Detection DLQ hand-off retry partition={} offset={} attempt={}/{} "
                                + "published={} category={} nextDelayMs={} reason={}",
                        handoff.partition(), handoff.offset(), attemptsThisRound, limit,
                        dlqPublished, retryable.category().metricTag(),
                        jitterCeiling(delay), dlqFailure.getMessage());

                if (attemptsThisRound >= limit) {
                    count("socp.detection.dlq.handoff", "retry_round_exhausted");
                    attemptsThisRound = 0;
                    // Compatibility/direct callers carry no Kafka partition and
                    // therefore have no session-owned scheduling responsibility.
                    if (partition == null) return false;
                }
                if (!sleepRetry(jitteredDelay(delay))) return false;
                delay = Math.min(RETRY_MAX.toMillis(), Math.max(1L, delay * 2));
            }
        }
        return false;
    }

    private static long jitteredDelay(long baseDelay) {
        long base = Math.max(1L, baseDelay);
        long spread = Math.max(1L, base / 5L);
        long lower = Math.max(1L, base - spread);
        long upper = Math.min(RETRY_MAX.toMillis(), base + spread);
        return lower >= upper ? lower : ThreadLocalRandom.current().nextLong(lower, upper + 1L);
    }

    private static long jitterCeiling(long baseDelay) {
        long base = Math.max(1L, baseDelay);
        return Math.min(RETRY_MAX.toMillis(), base + Math.max(1L, base / 5L));
    }

    /**
     * One durable dead-letter decision. {@code eventId} is the journal identity -
     * the normalized event id, or the canonical {@code kafka-offset:P:O} position
     * key when nothing parsed - and never the Kafka routing key, which is only
     * carried as DLQ metadata for entity-level correlation.
     */
    private record DlqHandoff(String eventId, String tenant, String routingKey, String raw,
                              Integer partition, Long offset, String reason, Headers sourceHeaders) {
    }

    /**
     * Writes the journal's terminal row under the event's own tenant. The lane
     * thread has no tenant scope left once processing failed, and the store
     * rejects tenant-less rows, so installing the scope here is what makes the
     * DEAD_LETTERED receipt durable instead of silently skipped.
     */
    private void recordTerminalJournalRow(DlqHandoff handoff) {
        if (handoff.tenant() == null || handoff.tenant().isBlank()) {
            // No tenant evidence exists for an unparseable payload. The store
            // logs and skips; the DLQ entry above remains the durable evidence.
            stateStore.recordDeadLettered(handoff.eventId(), handoff.raw(), handoff.partition(),
                    handoff.offset(), handoff.reason());
            return;
        }
        com.socp.platform.tenant.context.TenantContext.runWith(handoff.tenant(),
                () -> stateStore.recordDeadLettered(handoff.eventId(), handoff.raw(),
                        handoff.partition(), handoff.offset(), handoff.reason()));
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
        Map<TopicPartition, OffsetAndMetadata> ready = completionTracker.ready(configuredTopic());
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

    private void processOne(String topic, Integer partition, Long offset, String key, String raw) {
        recordProcessor.process(topic, partition, offset, key, raw);
    }

    private void rebuildOwnedState(int failedPartition) {
        Set<Integer> owned = engine.assignedPartitions();
        // RuleEngine is instance-wide, not partition-local. Replacing it from
        // only the failed partition would silently discard hot windows for the
        // other partitions still owned by this consumer.
        engine.rebuildForPartitions(owned == null || owned.isEmpty()
                ? Set.of(failedPartition) : owned);
    }

    /** Routing identity for DLQ metadata; never the identity of a terminal row. */
    private static String routingKeyOf(SecurityEvent event) {
        try {
            return com.socp.rule.partition.DetectionRoutingKey.forEvent(event);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private void publishDlqAndAwait(DlqHandoff handoff) throws Exception {
        if (customDlqSink) {
            dlqSink.accept(handoff.eventId(), handoff.raw());
            return;
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic + "-dlq",
                handoff.eventId() == null ? "unknown" : handoff.eventId(), handoff.raw());
        // The dead-letter entry inherits the trace of the record it replaces, so
        // an operator looking at the DLQ lands in the trace that failed instead
        // of a detached one. Journal replay has no source headers to inherit.
        KafkaTrace.inject(KafkaTrace.extract(handoff.sourceHeaders()), record.headers());
        // The Kafka routing key stays visible as metadata so a DLQ can still be
        // scanned per entity, but it is never the record key: the routing key is
        // shared by every event of one entity and would collapse distinct records.
        if (handoff.routingKey() != null && !handoff.routingKey().isBlank()) {
            record.headers().add("detection-routing-key", handoff.routingKey()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        KafkaClientSupport.sendAndAwait(dlq(), record, Duration.ofSeconds(30));
    }

    private static long estimateRecordBytes(
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record) {
        if (record == null) return 1L;
        long bytes = 256L;
        if (record.key() != null) {
            bytes += record.key().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (record.value() != null) {
            bytes += record.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        return Math.max(1L, bytes);
    }

    private static long estimateEventBytes(SecurityEvent event) {
        if (event == null) return 1L;
        long bytes = 256L + (event.raw() == null ? 0L
                : event.raw().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        if (event.fields() != null) {
            for (Map.Entry<String, String> field : event.fields().entrySet()) {
                bytes += field.getKey() == null ? 0L
                        : field.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                bytes += field.getValue() == null ? 0L
                        : field.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            }
        }
        return Math.max(1L, bytes);
    }

    private static void releaseDropped(List<Runnable> dropped) {
        if (dropped == null) return;
        for (Runnable task : dropped) {
            if (task instanceof PendingWork work) work.release();
        }
    }

    private static void releaseDeferred(ArrayDeque<PendingWork> deferred) {
        if (deferred == null) return;
        PendingWork work;
        while ((work = deferred.pollFirst()) != null) work.release();
    }

    /** Counts a task until it finishes or is explicitly discarded on revoke. */
    private static final class PendingWork implements Runnable {
        private final Runnable delegate;
        private final long bytes;
        private final AtomicLong counter;
        private final AtomicBoolean released = new AtomicBoolean();

        private PendingWork(Runnable delegate, long bytes, AtomicLong counter) {
            this.delegate = delegate;
            this.bytes = bytes;
            this.counter = counter;
        }

        @Override
        public void run() {
            try {
                delegate.run();
            } finally {
                release();
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) counter.addAndGet(-bytes);
        }
    }

    private record RecordCompletion(int partition, long offset, long epoch) {
    }

}
