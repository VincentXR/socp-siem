package com.socp.search.config.infrastructure.kafka;

import com.socp.platform.data.outbox.OutboxRetryPolicy;

import jakarta.annotation.PreDestroy;
import com.socp.search.config.config.IngestRuntimeProperties;
import com.socp.search.config.domain.IngestionOutboxEvent;
import com.socp.search.config.persistence.repository.IngestionOutboxRepository;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Reliably drains canonical-event publication intents to Kafka. */
@Component
public class IngestionOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestionOutboxPublisher.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final int DEFAULT_MAX_DRAIN_ROUNDS = 64;
    private static final long DEFAULT_MAX_DRAIN_DURATION_MS = 2_000L;
    private static final int MAX_ERROR_LENGTH = 1024;

    private final IngestionOutboxRepository repository;
    private final KafkaEventProducer producer;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executor;
    private final ExecutorService triggerExecutor;
    private final int maxAttempts;
    private final long retentionMs;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;
    private final int maxDrainRounds;
    private final long maxDrainDurationNanos;
    private final AtomicInteger claimBatchSizeGauge = new AtomicInteger();
    private final AtomicLong pendingCountGauge = new AtomicLong();
    private final AtomicLong oldestPendingAgeSecondsGauge = new AtomicLong();
    private final AtomicInteger drainRoundsGauge = new AtomicInteger();
    private final AtomicLong drainDurationMsGauge = new AtomicLong();
    private Instant nextRecoveryAt = Instant.EPOCH;

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
        this.executor = Executors.newFixedThreadPool(bounded,
                Thread.ofVirtual().name("ingestion-outbox-", 0).factory());
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
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.drain.rounds",
                    drainRoundsGauge, AtomicInteger::get).register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("socp.ingestion.outbox.drain.duration.milliseconds",
                    drainDurationMsGauge, AtomicLong::get).register(meterRegistry);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean activeTrigger = new java.util.concurrent.atomic.AtomicBoolean(false);

    IngestionOutboxPublisher(IngestionOutboxRepository repository, KafkaEventProducer producer) {
        this(repository, producer, null, 1, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS, 1_000, 10);
    }

    /** Triggers an immediate asynchronous ingestion outbox publication cycle on transaction commit. */
    public void triggerAsync() {
        if (!producer.isEnabled()) return;
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
        if (!producer.isEnabled()) return;
        long started = System.nanoTime();
        int rounds = 0;
        int lastBatchSize = 0;
        try {
            Instant now = Instant.now();
            int recovered = recoverStaleIfDue(now);
            if (recovered > 0) lifecycle("recovered", recovered);
            int exhausted = repository.markExhausted(maxAttempts, "retry limit reached", now);
            if (exhausted > 0) {
                log.error("Ingestion outbox rows moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            while (rounds < maxDrainRounds && System.nanoTime() - started < maxDrainDurationNanos) {
                now = Instant.now();
                List<IngestionOutboxEvent> pending =
                        repository.findTop200ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscCreatedAtAsc(
                                "PENDING", now);
                lastBatchSize = pending.size();
                if (pending.isEmpty()) break;
                rounds++;
                List<CompletableFuture<Void>> deliveries = pending.stream()
                        .map(event -> CompletableFuture.runAsync(
                                () -> TenantContext.runWith(event.getTenantId(), () -> deliver(event)),
                                executor))
                        .toList();
                CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();
                if (pending.size() < 200) break;
            }
            updateBacklogMetrics(lastBatchSize, Instant.now());
        } catch (Exception failure) {
            log.warn("Ingestion outbox scan failed; next scan will retry: {}", failure.getMessage());
        } finally {
            recordDrain(rounds, System.nanoTime() - started);
        }
    }

    private void recordDrain(int rounds, long durationNanos) {
        if (meterRegistry == null) return;
        drainRoundsGauge.set(rounds);
        drainDurationMsGauge.set(Math.max(0L, Duration.ofNanos(durationNanos).toMillis()));
    }

    private void updateBacklogMetrics(int claimBatchSize, Instant now) {
        if (meterRegistry == null) return;
        claimBatchSizeGauge.set(claimBatchSize);
        try {
            pendingCountGauge.set(repository.countByStatus("PENDING"));
            Instant oldest = repository.findOldestCreatedAtByStatus("PENDING");
            oldestPendingAgeSecondsGauge.set(oldest == null
                    ? 0 : Math.max(0, Duration.between(oldest, now).toSeconds()));
        } catch (RuntimeException failure) {
            log.warn("Ingestion outbox backlog metrics deferred: {}", failure.getMessage());
        }
    }

    private int recoverStaleIfDue(Instant now) {
        if (now.isBefore(nextRecoveryAt)) return 0;
        int recovered = repository.recoverStale(now.minus(Duration.ofMinutes(2)), now);
        nextRecoveryAt = now.plus(Duration.ofSeconds(30));
        return recovered;
    }

    private void deliver(IngestionOutboxEvent event) {
        boolean claimed = false;
        try {
            if (repository.claim(event.getId(), Instant.now(), maxAttempts) != 1) return;
            claimed = true;
            if (!producer.sendAndAwait(event.getRoutingKey(), event.getPayload(), event.getTraceparent())) {
                scheduleRetry(event, "Kafka broker did not acknowledge the event");
                return;
            }
            if (repository.markPublished(event.getId(), Instant.now()) != 1) {
                log.warn("Ingestion outbox state changed after broker acknowledgement id={}", event.getId());
            }
        } catch (Exception failure) {
            if (claimed) {
                scheduleRetry(event, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            log.warn("Ingestion outbox delivery failed id={}: {}", event.getId(), failure.getMessage());
        }
    }

    private void scheduleRetry(IngestionOutboxEvent event, String error) {
        Instant now = Instant.now();
        var decision = OutboxRetryPolicy.afterClaim(
                event.getAttempts() + 1, maxAttempts, now, error, 900);
        try {
            if (decision.exhausted()) {
                if (repository.markDead(event.getId(), decision.error(), now) == 1) {
                    log.error("Ingestion outbox moved to DEAD id={} eventId={} attempts={} reason={}",
                            event.getId(), event.getEventId(), decision.attempts(), decision.error());
                    lifecycle("dead", 1);
                }
                return;
            }
            if (repository.scheduleRetry(event.getId(), decision.nextAttemptAt(), decision.error(), now) == 1) {
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
        triggerExecutor.shutdownNow();
        executor.shutdownNow();
    }
}
