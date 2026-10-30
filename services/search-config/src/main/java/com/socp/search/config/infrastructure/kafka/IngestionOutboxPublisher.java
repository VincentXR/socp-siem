package com.socp.search.config.infrastructure.kafka;

import com.socp.platform.data.outbox.OutboxRetryPolicy;
import com.socp.platform.data.outbox.OutboxDeliveryExecutor;

import jakarta.annotation.PreDestroy;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.config.SearchRuntimeRole;
import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.search.config.service.IngestionOutboxBatchService;
import com.socp.search.config.service.port.IngestionPublicationTrigger;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Reliably drains canonical-event publication intents to Kafka. */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.WORKER)
public class IngestionOutboxPublisher implements IngestionPublicationTrigger {

    private static final Logger log = LoggerFactory.getLogger(IngestionOutboxPublisher.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final int DEFAULT_MAX_DRAIN_ROUNDS = 64;
    private static final long DEFAULT_MAX_DRAIN_DURATION_MS = 2_000L;
    private static final Duration DISCARDED_RETENTION = Duration.ofDays(30);
    private static final int MAX_ERROR_LENGTH = 1024;
    private static final int INDEPENDENT_COHORT_CONCURRENCY = 2;

    private enum Operation {
        SELECTION("selection"), EXHAUSTED("exhausted"), STALE("stale"),
        BACKLOG_METRICS("backlog_metrics"), RECEIPT_WAIT("receipt_wait"), DRAIN("drain");
        private final String label;
        Operation(String label) { this.label = label; }
    }

    private final IngestionOutboxRepository repository;
    private final KafkaEventProducer producer;
    private final MeterRegistry meterRegistry;
    private final Timer[][] operationTimers = new Timer[Operation.values().length][2];
    private final OutboxDeliveryExecutor executor;
    private final OutboxDeliveryExecutor cohortExecutor;
    private final ExecutorService triggerExecutor;
    private final int maxAttempts;
    private final long retentionMs;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;
    private final int maxDrainRounds;
    private final long maxDrainDurationNanos;
    private IngestionOutboxBatchService batchService;
    private final AtomicInteger claimBatchSizeGauge = new AtomicInteger();
    private final AtomicLong pendingCountGauge = new AtomicLong();
    private final AtomicLong oldestPendingAgeSecondsGauge = new AtomicLong();
    private final AtomicLong deadCountGauge = new AtomicLong();
    private final AtomicLong oldestDeadAgeSecondsGauge = new AtomicLong();
    private final AtomicInteger drainRoundsGauge = new AtomicInteger();
    private final AtomicLong drainDurationMsGauge = new AtomicLong();
    private volatile Instant nextRecoveryAt = Instant.EPOCH;

    @Autowired
    public IngestionOutboxPublisher(IngestionOutboxRepository repository,
                                    KafkaEventProducer producer,
                                    MeterRegistry meterRegistry,
                                    IngestRuntimeProperties properties) {
        this(repository, producer, meterRegistry,
                properties.getOutbox().getDeliveryConcurrency(),
                properties.getOutbox().getMaxAttempts(),
                properties.getOutbox().getRetentionMs(),
                properties.getOutbox().getCleanupBatchSize(),
                properties.getOutbox().getCleanupMaxBatches(),
                properties.getOutbox().getMaxDrainRounds(),
                properties.getOutbox().getMaxDrainDurationMs());
    }

    public IngestionOutboxPublisher(IngestionOutboxRepository repository,
                                    KafkaEventProducer producer,
                                    MeterRegistry meterRegistry,
                                    int concurrency, int maxAttempts, long retentionMs,
                                    int cleanupBatchSize, int cleanupMaxBatches) {
        this(repository, producer, meterRegistry, concurrency, maxAttempts, retentionMs,
                cleanupBatchSize, cleanupMaxBatches,
                DEFAULT_MAX_DRAIN_ROUNDS, DEFAULT_MAX_DRAIN_DURATION_MS);
    }

    public IngestionOutboxPublisher(IngestionOutboxRepository repository,
                                    KafkaEventProducer producer,
                                    MeterRegistry meterRegistry,
                                    int concurrency, int maxAttempts, long retentionMs,
                                    int cleanupBatchSize, int cleanupMaxBatches,
                                    int maxDrainRounds, long maxDrainDurationMs) {
        this.repository = repository;
        this.producer = producer;
        this.meterRegistry = meterRegistry;
        int bounded = Math.max(1, Math.min(32, concurrency));
        this.executor = new OutboxDeliveryExecutor("ingestion-outbox-", bounded);
        this.cohortExecutor = new OutboxDeliveryExecutor("ingestion-outbox-cohort-", INDEPENDENT_COHORT_CONCURRENCY);
        this.triggerExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("ingestion-outbox-trigger-", 0).factory());
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retentionMs = Math.max(Duration.ofMinutes(1).toMillis(), retentionMs);
        this.cleanupBatchSize = Math.max(1, Math.min(10_000, cleanupBatchSize));
        this.cleanupMaxBatches = Math.max(1, Math.min(100, cleanupMaxBatches));
        this.maxDrainRounds = Math.max(1, Math.min(1_000, maxDrainRounds));
        this.maxDrainDurationNanos = Duration.ofMillis(
                Math.max(10L, Math.min(60_000L, maxDrainDurationMs))).toNanos();
        if (meterRegistry != null) {
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.claim.batch.size",
                    claimBatchSizeGauge, AtomicInteger::get).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.pending.count",
                    pendingCountGauge, AtomicLong::get).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.oldest.pending.age.seconds",
                    oldestPendingAgeSecondsGauge, AtomicLong::get).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.dead.count",
                    deadCountGauge, AtomicLong::get).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.oldest.dead.age.seconds",
                    oldestDeadAgeSecondsGauge, AtomicLong::get).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.drain.rounds",
                    drainRoundsGauge, AtomicInteger::get).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.drain.duration.milliseconds",
                    drainDurationMsGauge, AtomicLong::get).register(meterRegistry);
            for (Operation operation : Operation.values()) {
                for (int outcome = 0; outcome < 2; outcome++) {
                    try {
                        operationTimers[operation.ordinal()][outcome] = meterRegistry.timer(
                                "socp.ingestion.outbox.operation.duration", "operation", operation.label,
                                "outcome", outcome == 0 ? "success" : "failure");
                    } catch (RuntimeException metricsFailure) {
                        log.debug("Ingestion outbox operation timer unavailable: {}", metricsFailure.getMessage());
                    }
                }
            }
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean activeDrain = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean activeTrigger = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean stopping = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Autowired
    void configureBatchService(IngestionOutboxBatchService batchService) {
        this.batchService = batchService;
    }

    IngestionOutboxPublisher(IngestionOutboxRepository repository, KafkaEventProducer producer) {
        this(repository, producer, null, 1, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS, 1_000, 10);
    }

    /** Triggers an immediate asynchronous ingestion outbox publication cycle on transaction commit. */
    public void triggerAsync() {
        if (stopping.get() || !producer.isEnabled()) return;
        if (activeTrigger.compareAndSet(false, true)) {
            triggerExecutor.execute(() -> {
                try {
                    // Publishing scans all tenants; the async path bypasses
                    // the @Scheduled proxy and must set system scope itself.
                    TenantContext.runAsSystem(this::publish);
                } finally {
                    activeTrigger.set(false);
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${socp.ingest.outbox.poll-interval-ms:500}",
            initialDelayString = "${socp.ingest.outbox.initial-delay-ms:1000}")
    @TenantSystemJob
    public void publish() {
        if (stopping.get()) return;
        if (!producer.isEnabled()) {
            updateBacklogMetrics(0, Instant.now());
            return;
        }
        if (!activeDrain.compareAndSet(false, true)) return;
        long started = System.nanoTime();
        int rounds = 0;
        int lastBatchSize = 0;
        long admissionDeadline = 0;
        java.util.Set<String> attempted = new java.util.HashSet<>();
        java.util.Set<String> blockedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
        boolean drainSucceeded = false;
        try {
            Instant maintenanceAt = Instant.now();
            int recovered = recoverStaleIfDue(maintenanceAt);
            if (recovered > 0) lifecycle("recovered", recovered);
            int exhausted = timed(Operation.EXHAUSTED,
                    () -> repository.markExhaustedBatch(maxAttempts, "retry limit reached", maintenanceAt, 100));
            if (exhausted > 0) {
                log.error("Ingestion outbox rows moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            while (rounds < maxDrainRounds && (rounds == 0 || System.nanoTime() < admissionDeadline)) {
                Instant selectionAt = Instant.now();
                List<IngestionOutboxEvent> pending = timed(Operation.SELECTION,
                        () -> repository.findDueOrderedBatch(selectionAt)).stream()
                        .filter(event -> attempted.add(event.getId())).toList();
                lastBatchSize = pending.size();
                if (pending.isEmpty()) break;
                // Selection has its own bounded database timeout. A slow first
                // scan must not hand an already-expired delivery deadline to
                // the executor forever, starving every otherwise eligible row.
                // Subsequent scans still consume this same admission budget.
                if (rounds == 0) admissionDeadline = System.nanoTime() + maxDrainDurationNanos;
                rounds++;
                if (batchService != null && independentKeys(pending)) {
                    deliverIndependent(pending, admissionDeadline, blockedKeys);
                } else {
                    deliverOrdered(pending, admissionDeadline, blockedKeys);
                }
                // Each selection admits at most 16 rows per key and 200 overall.
                // Per-row durable claims still fence every predecessor; a failed
                // head stops the rest of that key's local batch for this drain.
                // Never attempt the same row twice in one drain (lost claims or
                // retries must yield instead of becoming a database busy loop).
            }
            drainSucceeded = true;
        } catch (Exception failure) {
            log.warn("Ingestion outbox scan failed; next scan will retry: {}", failure.toString());
        } finally {
            activeDrain.set(false);
            boolean backlogSucceeded = updateBacklogMetrics(lastBatchSize, Instant.now());
            recordDrain(rounds, System.nanoTime() - started);
            recordOperation(Operation.DRAIN, started, drainSucceeded && backlogSucceeded);
        }
    }

    private static String deliveryKey(IngestionOutboxEvent event) {
        return event.getTenantId() + "\u0000" + event.getRoutingKey();
    }

    private static boolean independentKeys(List<IngestionOutboxEvent> events) {
        var keys = new java.util.HashSet<String>();
        return events.stream().allMatch(event -> keys.add(deliveryKey(event)));
    }

    private void deliverOrdered(List<IngestionOutboxEvent> pending, long deadline,
                                java.util.Set<String> blockedKeys) {
        executor.deliverKeyed(pending, deadline, IngestionOutboxPublisher::deliveryKey, event -> {
            String key = deliveryKey(event);
            if (!blockedKeys.contains(key)
                    && !TenantContext.callWith(event.getTenantId(), () -> deliver(event))) {
                blockedKeys.add(key);
            }
        });
    }

    private void deliverIndependent(List<IngestionOutboxEvent> candidates, long deadline,
                                    java.util.Set<String> blockedKeys) {
        var tenants = new java.util.LinkedHashMap<String, java.util.List<IngestionOutboxEvent>>();
        for (IngestionOutboxEvent event : candidates) {
            if (!blockedKeys.contains(deliveryKey(event))) {
                tenants.computeIfAbsent(event.getTenantId(), ignored -> new java.util.ArrayList<>()).add(event);
            }
        }
        var cohorts = new java.util.ArrayList<List<IngestionOutboxEvent>>();
        for (var events : tenants.values()) {
            for (int start = 0; start < events.size(); start += IngestionOutboxBatchService.MAX_BATCH_SIZE) {
                cohorts.add(List.copyOf(events.subList(start,
                        Math.min(events.size(), start + IngestionOutboxBatchService.MAX_BATCH_SIZE))));
            }
        }
        // Admit at most two cohorts across all tenants before acquiring any
        // durable claims. The shared submission executor retains its own bound.
        // Every admitted cohort joins its receipts and settles claims before
        // this call returns, including when admission expires or a peer fails.
        cohortExecutor.deliver(cohorts, deadline, cohort -> {
            if (stopping.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return;
            TenantContext.runWith(cohort.getFirst().getTenantId(), () -> deliverCohort(cohort, deadline, blockedKeys));
        });
    }

    private void deliverCohort(List<IngestionOutboxEvent> candidates, long deadline,
                               java.util.Set<String> blockedKeys) {
        String token = java.util.UUID.randomUUID().toString();
        List<IngestionOutboxEvent> claimed;
        long claimStarted = System.nanoTime();
        long deliveryDeadline = claimStarted + deliveryAdmissionBudgetNanos();
        try {
            claimed = batchService.claimIndependent(candidates, token, maxAttempts, Instant.now());
            recordBatch("claim", claimed.size(), claimStarted, true);
        } catch (RuntimeException failedClaim) {
            recordBatch("claim", 0, claimStarted, false);
            // No batch receipts returned. Re-enter the per-row CAS path so even
            // an ambiguous commit is rechecked before any publication attempt.
            log.debug("Independent outbox claim batch fell back to individual claims: {}", failedClaim.toString());
            deliverOrdered(candidates, deadline, blockedKeys);
            return;
        }
        var acknowledged = new java.util.ArrayList<IngestionOutboxEvent>();
        var receipts = new java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>>();
        var publicationStarted = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        try {
            // The bounded cohort was admitted before claiming. All returned claims
            // must now finish or be released, even if the drain deadline expired.
            // Limit potentially blocking submissions with the configured executor,
            // but let up to 32 independent receipts await Kafka concurrently.
            // Claim latency consumes the 35s admission budget; each started
            // submission retains its 3s metadata + 30s acknowledgement budget.
            executor.deliver(claimed, deliveryDeadline, event -> TenantContext.runWith(event.getTenantId(), () -> {
                try {
                    publicationStarted.add(event.getId());
                    receipts.put(event.getId(), java.util.Objects.requireNonNull(producer.sendAsync(
                            event.getRoutingKey(), event.getPayload(), event.getTraceparent())));
                } catch (RuntimeException failure) {
                    receipts.put(event.getId(), java.util.concurrent.CompletableFuture.completedFuture(false));
                }
            }));
        } finally {
            // The executor joins all admitted submissions, including on shutdown.
            // Join every resulting receipt before retry/finalization. Callbacks
            // only complete receipts; all database writes retain this tenant scope.
            long waitStarted = System.nanoTime();
            boolean waitSucceeded = false;
            try {
                waitSucceeded = java.util.concurrent.CompletableFuture.allOf(receipts.values().toArray(
                        java.util.concurrent.CompletableFuture[]::new)).handle((ignored, failure) -> failure == null).join();
            } finally {
                recordOperation(Operation.RECEIPT_WAIT, waitStarted, waitSucceeded);
            }
            for (IngestionOutboxEvent event : claimed) {
                var receipt = receipts.get(event.getId());
                if (receipt != null) {
                    boolean success;
                    try { success = Boolean.TRUE.equals(receipt.join()); }
                    catch (RuntimeException failedReceipt) { success = false; }
                    if (success) acknowledged.add(event);
                    else {
                        scheduleRetry(event, token, "Kafka broker did not acknowledge the event");
                        blockedKeys.add(deliveryKey(event));
                    }
                } else if (!publicationStarted.contains(event.getId())) {
                    releaseUnsentClaim(event, token);
                    blockedKeys.add(deliveryKey(event));
                } else {
                    // A started submission without a receipt is ambiguous, never
                    // an unsent claim whose reserved attempt can be returned.
                    scheduleRetry(event, token, "publication submission did not return a receipt");
                    blockedKeys.add(deliveryKey(event));
                }
            }
            if (!acknowledged.isEmpty()) {
                List<IngestionOutboxEvent> completed = List.copyOf(acknowledged);
                long finalizeStarted = System.nanoTime();
                try {
                    int published = batchService.markAcknowledged(completed, token, Instant.now());
                    recordBatch("finalize", published, finalizeStarted, true);
                    if (published != completed.size()) {
                        completed.forEach(event -> blockedKeys.add(deliveryKey(event)));
                        log.warn("Ingestion outbox ownership changed after batch broker acknowledgement expected={} published={}",
                                completed.size(), published);
                    }
                } catch (RuntimeException failedFinalization) {
                    recordBatch("finalize", 0, finalizeStarted, false);
                    for (IngestionOutboxEvent event : completed) {
                        scheduleRetry(event, token, "acknowledgement persistence failed: " + failedFinalization.getMessage());
                        blockedKeys.add(deliveryKey(event));
                    }
                }
            }
        }
    }

    long deliveryAdmissionBudgetNanos() {
        return java.util.concurrent.TimeUnit.SECONDS.toNanos(35);
    }

    private <T> T timed(Operation operation, java.util.function.Supplier<T> action) {
        long started = System.nanoTime();
        boolean succeeded = false;
        try {
            T result = action.get();
            succeeded = true;
            return result;
        } finally {
            recordOperation(operation, started, succeeded);
        }
    }

    private void recordOperation(Operation operation, long started, boolean success) {
        Timer timer = operationTimers[operation.ordinal()][success ? 0 : 1];
        if (timer == null) return;
        try {
            timer.record(Math.max(0L, System.nanoTime() - started), java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (RuntimeException metricsFailure) {
            log.debug("Ingestion outbox operation measurement unavailable: {}", metricsFailure.getMessage());
        }
    }

    private void recordBatch(String operation, int items, long started, boolean success) {
        if (meterRegistry == null) return;
        try {
            String outcome = success ? "success" : "failure";
            meterRegistry.timer("socp.ingestion.outbox.batch.duration", "operation", operation, "outcome", outcome)
                    .record(Math.max(0L, System.nanoTime() - started), java.util.concurrent.TimeUnit.NANOSECONDS);
            meterRegistry.summary("socp.ingestion.outbox.batch.items", "operation", operation, "outcome", outcome)
                    .record(items);
        } catch (RuntimeException metricsFailure) {
            log.debug("Ingestion outbox batch metrics unavailable: {}", metricsFailure.getMessage());
        }
    }

    private void recordDrain(int rounds, long durationNanos) {
        if (meterRegistry == null) return;
        drainRoundsGauge.set(rounds);
        drainDurationMsGauge.set(Math.max(0L, Duration.ofNanos(durationNanos).toMillis()));
    }

    private boolean updateBacklogMetrics(int claimBatchSize, Instant now) {
        if (meterRegistry == null) return true;
        long started = System.nanoTime();
        boolean succeeded = false;
        claimBatchSizeGauge.set(claimBatchSize);
        try {
            pendingCountGauge.set(repository.countByStatus("PENDING"));
            Instant oldest = repository.findOldestCreatedAtByStatus("PENDING");
            oldestPendingAgeSecondsGauge.set(oldest == null
                    ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds()));
            deadCountGauge.set(repository.countByStatus("DEAD"));
            Instant oldestDead = repository.findOldestUpdatedAtByStatus("DEAD");
            oldestDeadAgeSecondsGauge.set(oldestDead == null
                    ? 0 : Math.max(0, Duration.between(oldestDead, now).toSeconds()));
            succeeded = true;
        } catch (RuntimeException failure) {
            log.warn("Ingestion outbox backlog metrics deferred: {}", failure.toString());
        } finally {
            recordOperation(Operation.BACKLOG_METRICS, started, succeeded);
        }
        return succeeded;
    }

    private int recoverStaleIfDue(Instant now) {
        if (now.isBefore(nextRecoveryAt)) return 0;
        int recovered = timed(Operation.STALE,
                () -> repository.recoverStaleBatch(now.minus(Duration.ofMinutes(2)), now, 100));
        nextRecoveryAt = now.plus(Duration.ofSeconds(30));
        return recovered;
    }

    private boolean deliver(IngestionOutboxEvent event) {
        String token = java.util.UUID.randomUUID().toString();
        boolean claimed = false;
        try {
            try {
                if (repository.claim(event.getId(), Instant.now(), maxAttempts,
                        event.getAttempts(), token) != 1) return false;
            } catch (DataIntegrityViolationException competingKeyOwner) {
                // A concurrent replica won the unique processing-key lease after this
                // scan's snapshot. Leave this row pending for the next polling cycle.
                return false;
            }
            claimed = true;
            if (!producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent())) {
                scheduleRetry(event, token, "Kafka broker did not acknowledge the event");
                return false;
            }
            if (repository.markPublished(event.getId(), Instant.now(), token) != 1) {
                log.warn("Ingestion outbox state changed after broker acknowledgement id={}", event.getId());
                return false;
            }
            return true;
        } catch (Exception failure) {
            if (claimed) {
                scheduleRetry(event, token, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            log.warn("Ingestion outbox delivery failed id={}: {}", event.getId(), failure.getMessage());
            return false;
        }
    }

    private void scheduleRetry(IngestionOutboxEvent event, String token, String error) {
        Instant now = Instant.now();
        var decision = OutboxRetryPolicy.afterClaim(
                event.getAttempts() + 1, maxAttempts, now, error, 900);
        try {
            if (decision.exhausted()) {
                if (repository.markDead(event.getId(), decision.error(), now, token) == 1) {
                    log.error("Ingestion outbox moved to DEAD id={} eventId={} attempts={} reason={}",
                            event.getId(), event.getEventId(), decision.attempts(), decision.error());
                    lifecycle("dead", 1);
                }
                return;
            }
            if (repository.scheduleRetry(event.getId(), decision.nextAttemptAt(), decision.error(), now, token) == 1) {
                log.warn("Ingestion outbox retry scheduled id={} eventId={} attempts={} next={} reason={}",
                        event.getId(), event.getEventId(), decision.attempts(),
                        decision.nextAttemptAt(), decision.error());
                lifecycle("retry", 1);
            }
        } catch (RuntimeException stateFailure) {
            // Leave PROCESSING for stale-claim recovery when the database state update fails.
            log.warn("Ingestion outbox retry state update deferred id={}: {}",
                    event.getId(), stateFailure.getMessage());
        }
    }

    private void releaseUnsentClaim(IngestionOutboxEvent event, String token) {
        try {
            repository.releaseUnsentClaim(event.getId(), event.getTenantId(), Instant.now(), token);
        } catch (RuntimeException stateFailure) {
            // Unconfirmed releases remain PROCESSING for fenced stale-claim recovery.
            log.warn("Ingestion outbox unstarted claim release deferred id={}: {}",
                    event.getId(), stateFailure.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${socp.ingest.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.ingest.outbox.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupPublished() {
        try {
            Instant cutoff = Instant.now().minusMillis(retentionMs);
            int totalRemoved = 0;
            for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                int removed = repository.deletePublishedBatchBefore(cutoff, cleanupBatchSize);
                totalRemoved += removed;
                if (removed < cleanupBatchSize) break;
            }
            if (totalRemoved > 0) {
                log.info("Removed retained ingestion outbox rows count={} batchSize={} maxBatches={}",
                        totalRemoved, cleanupBatchSize, cleanupMaxBatches);
                lifecycle("cleaned", totalRemoved);
            }
            Instant discardedCutoff = Instant.now().minus(DISCARDED_RETENTION);
            int discardedRemoved = 0;
            for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                int removed = repository.deleteDiscardedBatchBefore(discardedCutoff, cleanupBatchSize);
                discardedRemoved += removed;
                if (removed < cleanupBatchSize) break;
            }
            if (discardedRemoved > 0) {
                log.info("Removed explicitly discarded ingestion outbox rows count={}", discardedRemoved);
                lifecycle("discarded_cleaned", discardedRemoved);
            }
        } catch (RuntimeException failure) {
            log.warn("Ingestion outbox retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private void lifecycle(String outcome, int count) {
        if (meterRegistry != null && count > 0) {
            meterRegistry.counter("socp.ingestion.outbox.lifecycle", "outcome", outcome).increment(count);
        }
    }

    private static String truncate(String error) {
        String value = error == null || error.isBlank() ? "unknown delivery failure" : error;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    @PreDestroy
    void stop() {
        stopping.set(true);
        triggerExecutor.shutdownNow();
        cohortExecutor.close();
        executor.close();
    }
}
