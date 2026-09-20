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

    @Value("${socp.kafka.topic:socp-events}")
    private String topic;

    @Value("${socp.kafka.group-id:socp-detect}")
    private String groupId;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    /**
     * Bounds repeated execution of a record whose failure is deterministic, and
     * bounds how long a record waits on a worker-wide failure. A deterministic
     * record hands off to the durable DLQ once the budget is spent; a record
     * blocked by global unavailability is withheld instead, so the offset stays
     * uncommitted and the journal row stays replayable.
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
    private final Map<TopicPartition, ArrayDeque<PendingWork>> deferredWork = new ConcurrentHashMap<>();
    /** In-flight, lane-queued and deferred bytes for each Kafka partition. */
    private final Map<TopicPartition, AtomicLong> pendingBytes = new ConcurrentHashMap<>();
    /** Partitions paused because their lane or deferred buffer is saturated. */
    private final Set<TopicPartition> pausedPartitions = ConcurrentHashMap.newKeySet();
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
    }

    private void count(String name, String outcome) {
        if (metrics == null) return;
        metrics.counter(name, "outcome", outcome).increment();
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
                        pausedPartitions.remove(partition);
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
                drainDeferred(consumer);
                var records = consumer.poll(Duration.ofMillis(250));
                for (var record : records) {
                    long epoch = completionTracker.register(record.partition(), record.offset());
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    dispatchOrDefer(consumer, partition, () -> processWithRetry(record, epoch),
                            estimateRecordBytes(record));
                }
                drainCompletions(consumer);
                drainDeferred(consumer);
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
        pausedPartitions.clear();
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
        pausedPartitions.add(partition);
        if (consumer != null) consumer.pause(Set.of(partition));
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
                pausedPartitions.remove(partition);
                if (consumer != null) consumer.resume(Set.of(partition));
            } else {
                pausedPartitions.add(partition);
                if (consumer != null) consumer.pause(Set.of(partition));
            }
        }

        // A partition can be paused while its deferred entry is being removed
        // by a concurrent lifecycle callback. Re-apply the set before poll so
        // no already-fetched records refill a saturated lane.
        if (consumer != null && !pausedPartitions.isEmpty()) {
            consumer.pause(new HashSet<>(pausedPartitions));
        }
    }

    private void replayPending(Set<Integer> partitions) {
        List<PendingDetectionEvent> pending = stateStore.pendingRecordsForPartitions(
                partitions, configuredReplayWindow());
        for (PendingDetectionEvent row : pending) {
            if (row == null || row.event() == null || row.partition() == null) continue;
            dispatchOrDefer(null, new TopicPartition(topic, row.partition()),
                    () -> processPendingWithRetry(row), estimateEventBytes(row.event()));
        }
        if (!pending.isEmpty()) {
            log.info("Queued bounded PENDING journal prefetch count={}; any remaining rows "
                    + "stay behind uncommitted Kafka offsets and will be redelivered", pending.size());
        }
    }

    /** Compatibility constructors do not have Spring property injection. */
    private Duration configuredReplayWindow() {
        return replayWindow == null ? Duration.ZERO : replayWindow;
    }

    /**
     * Package-private so the retry, dead-letter and completion decisions can be
     * driven with a real record; {@link #processRecord(String, String)} bypasses
     * this path because it carries no Kafka headers and therefore no trace
     * context.
     */
    void processWithRetry(org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
                          long epoch) {
        long delay = 250;
        int attempts = 0;

        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // Parented to the producer's span, so the Kafka hop joins the
                // same tree. Mirroring the trace-id into the MDC only made
                // both sides print one string; no exporter could join them.
                KafkaTrace.runConsumed("detect " + record.topic() + " receive", record.headers(), () -> {
                    // The normalized event is the source of truth for tenant
                    // ownership. DetectionRecordProcessor installs that scope
                    // after parsing, so a Kafka header can never re-home a row.
                    processOne(record.topic(), record.partition(), record.offset(), record.key(), record.value());
                    completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                });
                return;
            } catch (DetectionRecordProcessor.MalformedDetectionRecordException terminal) {
                // Nothing parsed, so no tenant is known: the store keeps its
                // locked contract and the Kafka DLQ record is the only evidence.
                if (handoffToDlqUntilDurable(new DlqHandoff(terminal.eventId(), null, record.key(),
                        terminal.raw(), record.partition(), record.offset(), terminal.getMessage(),
                        record.headers()))) {
                    completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                }
                return;
            } catch (Exception failure) {
                attempts++;
                int limit = Math.max(1, processingMaxAttempts);
                boolean unavailable =
                        failure instanceof DetectionRecordProcessor.DetectionUnavailableException;
                log.warn("Detection processing failed partition={} offset={} attempt={}/{} "
                                + "globallyUnavailable={} reason={}",
                        record.partition(), record.offset(), attempts, limit, unavailable,
                        failure.getMessage());

                if (attempts >= limit) {
                    // A worker-wide outage is not this record's fault: withhold
                    // the completion so the offset stays uncommitted and the
                    // PENDING journal row is replayed, rather than terminalising
                    // a live event into the DLQ.
                    if (unavailable) {
                        log.error("Detection processing withheld partition={} offset={} after "
                                        + "{} unavailable attempts; the offset stays uncommitted so the "
                                        + "record is redelivered once the worker recovers",
                                record.partition(), record.offset(), attempts);
                        count("socp.detection.processing.withheld", "globally_unavailable");
                    } else {
                        // The normalized event id is the journal identity. A Kafka
                        // routing key never is, so it is only kept as DLQ metadata.
                        DetectionRecordProcessor.TerminalDetectionFailure terminal =
                                failure instanceof DetectionRecordProcessor.TerminalDetectionFailure typed
                                        ? typed : null;
                        String eventId = terminal != null ? terminal.eventId()
                                : "kafka-offset:" + record.partition() + ":" + record.offset();
                        String tenant = terminal == null ? null : terminal.tenantId();
                        String reason = "processing attempts exhausted: " + failure.getMessage();
                        if (handoffToDlqUntilDurable(new DlqHandoff(eventId, tenant, record.key(),
                                record.value(), record.partition(), record.offset(), reason,
                                record.headers()))) {
                            completions.offer(new RecordCompletion(record.partition(), record.offset(), epoch));
                        }
                    }
                    return;
                }

                // RuleEngine state is instance-wide. Rebuild all currently
                // owned partitions only once for this failed record; repeated
                // full rebuilds turn a deterministic poison record into a storm.
                // A worker-wide outage is never state corruption, so rebuilding
                // for every withheld record would amplify one outage into a
                // rebuild per record on every lane.
                if (attempts == 1 && !unavailable) {
                    try {
                        rebuildOwnedState(record.partition());
                    } catch (Exception rebuildFailure) {
                        log.warn("Detection state rebuild deferred partition={}: {}",
                                record.partition(), rebuildFailure.getMessage());
                    }
                }
            } finally {
                com.socp.platform.tenant.context.TenantContext.clear();
            }
            if (!sleepRetry(delay)) return;
            delay = Math.min(RETRY_MAX.toMillis(), delay * 2);
        }
    }


    /** Package-private hook used by focused tests. */
    void processPendingWithRetry(PendingDetectionEvent row) {
        long delay = 250;
        int attempts = 0;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                com.socp.platform.tenant.context.TenantContext.runWith(
                        row.event().requireTenantId(),
                        () -> recordProcessor.processNormalized(row.partition(), row.offset(),
                                com.socp.rule.partition.DetectionRoutingKey.forEvent(row.event()),
                                row.event()));
                return;
            } catch (Exception failure) {
                attempts++;
                int limit = Math.max(1, processingMaxAttempts);
                boolean unavailable =
                        failure instanceof DetectionRecordProcessor.DetectionUnavailableException;
                log.warn("Pending Detection replay failed partition={} offset={} attempt={}/{} "
                                + "globallyUnavailable={} reason={}",
                        row.partition(), row.offset(), attempts, limit, unavailable, failure.getMessage());

                if (attempts >= limit) {
                    // A journal row replayed while the worker is still unavailable
                    // stays PENDING; only a deterministic failure terminalises it.
                    if (unavailable) {
                        log.error("Pending Detection replay withheld partition={} offset={} after "
                                        + "{} unavailable attempts; the row stays PENDING for the next "
                                        + "assignment",
                                row.partition(), row.offset(), attempts);
                        count("socp.detection.processing.withheld", "replay_globally_unavailable");
                    } else {
                        // A replayed journal row carries no Kafka headers, so the
                        // hand-off has no originating trace to inherit. The stored
                        // event supplies both the journal identity and its tenant.
                        handoffToDlqUntilDurable(new DlqHandoff(row.event().id(),
                                row.event().requireTenantId(),
                                routingKeyOf(row.event()), row.event().raw(), row.partition(), row.offset(),
                                "pending replay attempts exhausted: " + failure.getMessage(), null));
                    }
                    return;
                }

                if (attempts == 1 && !unavailable) {
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
     * Hands a record to the dead-letter topic and to its terminal journal row,
     * giving up after a bounded wait.
     * <p>
     * Both durable writes share one bounded retry because they are one decision:
     * an offset may only advance when the DLQ entry and the journal's
     * DEAD_LETTERED row both exist. This used to retry until the write succeeded,
     * which wedged the lane whenever the broker was unreachable: the record never
     * completed, no later batch on that partition could commit, and the only
     * signal was an ERROR log while every health probe stayed green. Returning
     * false is the safe failure: the caller withholds the completion, so the
     * offset stays uncommitted and the record is redelivered and re-attempted once
     * the broker is reachable again. The wait is bounded rather than unbounded
     * because a stuck lane stops progress on every other record behind it, so a
     * broker outage longer than the bound leaves this partition's commit pinned
     * at the gap until the next rebalance or consumer-session restart.
     */
    private boolean handoffToDlqUntilDurable(DlqHandoff handoff) {
        long delay = Math.max(0L, dlqHandoffRetryDelayMs);
        int limit = Math.max(1, dlqHandoffMaxAttempts);
        boolean dlqPublished = false;
        for (int attempt = 1; attempt <= limit; attempt++) {
            if (!running.get() || Thread.currentThread().isInterrupted()) return false;
            try {
                if (!dlqPublished) {
                    publishDlqAndAwait(handoff);
                    dlqPublished = true;
                }
                recordTerminalJournalRow(handoff);
                count("socp.detection.dlq.handoff", "committed");
                return true;
            } catch (Exception dlqFailure) {
                log.error("Detection DLQ hand-off unavailable partition={} offset={} attempt={}/{}; "
                                + "retrying in {}ms: {}",
                        handoff.partition(), handoff.offset(), attempt, limit, delay,
                        dlqFailure.getMessage());
                if (attempt == limit) break;
                if (!sleepRetry(delay)) return false;
                delay = Math.min(RETRY_MAX.toMillis(), delay * 2);
            }
        }
        log.error("Detection DLQ hand-off abandoned partition={} offset={} after {} attempts; "
                        + "the offset stays uncommitted so the record is redelivered",
                handoff.partition(), handoff.offset(), limit);
        count("socp.detection.dlq.handoff", "abandoned");
        return false;
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
