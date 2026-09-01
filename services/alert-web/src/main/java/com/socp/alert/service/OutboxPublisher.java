package com.socp.alert.service;

import com.socp.platform.data.outbox.OutboxRetryPolicy;

import com.socp.alert.config.AlertOutboxProperties;
import com.socp.alert.domain.OutboxEvent;
import com.socp.alert.repository.OutboxRepository;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;

import jakarta.annotation.PreDestroy;
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

/**
 * Publishes the durable Alert outbox to the fan-out Kafka topic.
 *
 * <p>A bounded query prevents an accumulated backlog from being materialized
 * in one scan. Optimistic claims make multiple alert-web instances safe, and
 * the bounded delivery executor lets the Kafka producer batch requests without
 * turning the scheduler into unbounded concurrency. A crash can still repeat
 * a broker-acknowledged event before the database state update; downstream
 * consumers therefore retain at-least-once idempotency.</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final int DEFAULT_MAX_DRAIN_ROUNDS = 64;
    private static final long DEFAULT_MAX_DRAIN_DURATION_MS = 2_000L;
    private static final int MAX_ERROR_LENGTH = 1024;

    private final OutboxRepository outboxRepo;
    private final AlertKafkaPublisher kafkaPublisher;
    private final AlertPerformanceMetrics performanceMetrics;
    private final ExecutorService deliveryExecutor;
    private final ExecutorService triggerExecutor;
    private final int maxAttempts;
    private final long retentionMs;
    private final int maxDrainRounds;
    private final long maxDrainDurationNanos;
    private Instant nextRecoveryAt = Instant.EPOCH;

    @Autowired
    public OutboxPublisher(OutboxRepository outboxRepo, AlertKafkaPublisher kafkaPublisher,
                           AlertPerformanceMetrics performanceMetrics,
                           AlertOutboxProperties properties) {
        this(outboxRepo, kafkaPublisher, performanceMetrics,
                properties.getDeliveryConcurrency(), properties.getMaxAttempts(), properties.getRetentionMs(),
                properties.getMaxDrainRounds(), properties.getMaxDrainDurationMs());
    }

    public OutboxPublisher(OutboxRepository outboxRepo, AlertKafkaPublisher kafkaPublisher,
                           AlertPerformanceMetrics performanceMetrics,
                           int concurrency, int maxAttempts, long retentionMs) {
        this(outboxRepo, kafkaPublisher, performanceMetrics, concurrency, maxAttempts, retentionMs,
                DEFAULT_MAX_DRAIN_ROUNDS, DEFAULT_MAX_DRAIN_DURATION_MS);
    }

    public OutboxPublisher(OutboxRepository outboxRepo, AlertKafkaPublisher kafkaPublisher,
                           AlertPerformanceMetrics performanceMetrics,
                           int concurrency, int maxAttempts, long retentionMs,
                           int maxDrainRounds, long maxDrainDurationMs) {
        this.outboxRepo = outboxRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.performanceMetrics = performanceMetrics;
        int bounded = Math.max(1, Math.min(32, concurrency));
        this.deliveryExecutor = Executors.newFixedThreadPool(bounded,
                Thread.ofVirtual().name("alert-outbox-delivery-", 0).factory());
        this.triggerExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("alert-outbox-trigger-", 0).factory());
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retentionMs = Math.max(Duration.ofMinutes(1).toMillis(), retentionMs);
        this.maxDrainRounds = Math.max(1, Math.min(1_000, maxDrainRounds));
        this.maxDrainDurationNanos = Duration.ofMillis(
                Math.max(10L, Math.min(60_000L, maxDrainDurationMs))).toNanos();
    }

    private final java.util.concurrent.atomic.AtomicBoolean activeTrigger = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Unit-test/source compatibility constructor. */
    OutboxPublisher(OutboxRepository outboxRepo, AlertKafkaPublisher kafkaPublisher) {
        this(outboxRepo, kafkaPublisher, null, 1, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS);
    }

    /** Triggers an immediate asynchronous outbox publish cycle on transaction commit. */
    public void triggerAsync() {
        if (!kafkaPublisher.isAvailable()) return;
        if (activeTrigger.compareAndSet(false, true)) {
            triggerExecutor.execute(() -> {
                try {
                    // The scan is cross-tenant maintenance work.  Scheduled
                    // invocations are wrapped by ScheduledSystemScopeAspect,
                    // but this direct executor path bypasses Spring AOP.
                    TenantContext.runAsSystem(this::publish);
                } finally {
                    activeTrigger.set(false);
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${socp.alert.outbox.poll-interval-ms:1000}",
            initialDelayString = "${socp.alert.outbox.initial-delay-ms:1000}")
    @TenantSystemJob
    public void publish() {
        long started = System.nanoTime();
        int rounds = 0;
        try {
            Instant now = Instant.now();
            int recovered = recoverStaleIfDue(now);
            if (recovered > 0) {
                log.warn("Recovered stale Alert outbox claims count={}", recovered);
                lifecycle("recovered", recovered);
            }
            int exhausted = outboxRepo.markExhausted(maxAttempts, "retry limit reached", now);
            if (exhausted > 0) {
                log.error("Alert outbox rows moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            while (rounds < maxDrainRounds && System.nanoTime() - started < maxDrainDurationNanos) {
                now = Instant.now();
                List<OutboxEvent> pending = outboxRepo
                        .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
                if (pending.isEmpty()) break;
                if (!kafkaPublisher.isAvailable()) {
                    log.warn("Kafka unavailable; Alert outbox remains pending count={}", pending.size());
                    break;
                }
                rounds++;
                List<CompletableFuture<Void>> deliveries = pending.stream()
                        .map(event -> CompletableFuture.runAsync(
                                () -> TenantContext.runWith(event.getTenantId(), () -> deliver(event)),
                                deliveryExecutor))
                        .toList();
                CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();
                if (pending.size() < 100) break;
            }
        } catch (Exception failure) {
            log.warn("Alert outbox scan failed; next scan will retry: {}", failure.getMessage());
        } finally {
            if (performanceMetrics != null) {
                performanceMetrics.outboxDrain("alarm_event", rounds, System.nanoTime() - started);
            }
        }
    }

    private int recoverStaleIfDue(Instant now) {
        if (now.isBefore(nextRecoveryAt)) return 0;
        int recovered = outboxRepo.recoverStale(now.minus(Duration.ofMinutes(2)), now);
        nextRecoveryAt = now.plus(Duration.ofSeconds(30));
        return recovered;
    }

    private void deliver(OutboxEvent event) {
        boolean claimed = false;
        try {
            if (outboxRepo.claim(event.getId(), Instant.now(), maxAttempts) != 1) return;
            claimed = true;
            if (!kafkaPublisher.sendAlarmEventAndAwait(event.getAggregateId(), event.getPayload())) {
                scheduleRetry(event, "Kafka broker did not acknowledge the event");
                return;
            }
            if (outboxRepo.markPublished(event.getId(), Instant.now()) != 1) {
                log.warn("Alert outbox publish state changed unexpectedly id={}", event.getId());
            }
        } catch (Exception failure) {
            if (claimed) {
                scheduleRetry(event, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            log.warn("Alert outbox delivery failed id={}: {}", event.getId(), failure.getMessage());
        }
    }

    private void scheduleRetry(OutboxEvent event, String error) {
        Instant now = Instant.now();
        var decision = OutboxRetryPolicy.afterClaim(
                event.getAttempts() + 1, maxAttempts, now, error, 900);
        try {
            if (decision.exhausted()) {
                if (outboxRepo.markDead(event.getId(), decision.error(), now) == 1) {
                    log.error("Alert outbox moved to DEAD id={} aggregateId={} attempts={} reason={}",
                            event.getId(), event.getAggregateId(), decision.attempts(), decision.error());
                    lifecycle("dead", 1);
                }
                return;
            }
            if (outboxRepo.scheduleRetry(event.getId(), decision.nextAttemptAt(), decision.error(), now) == 1) {
                log.warn("Alert outbox retry scheduled id={} aggregateId={} attempts={} next={} reason={}",
                        event.getId(), event.getAggregateId(), decision.attempts(),
                        decision.nextAttemptAt(), decision.error());
                lifecycle("retry", 1);
            }
        } catch (RuntimeException stateFailure) {
            // A failed state write deliberately leaves PROCESSING for stale-claim recovery.
            log.warn("Alert outbox retry state update deferred id={}: {}",
                    event.getId(), stateFailure.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${socp.alert.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.alert.outbox.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupPublished() {
        try {
            int removed = outboxRepo.deletePublishedBefore(Instant.now().minusMillis(retentionMs));
            if (removed > 0) {
                log.info("Removed retained Alert outbox rows count={}", removed);
                lifecycle("cleaned", removed);
            }
        } catch (RuntimeException failure) {
            log.warn("Alert outbox retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private void lifecycle(String outcome, int count) {
        if (performanceMetrics != null) performanceMetrics.outboxLifecycle("alarm_event", outcome, count);
    }

    private static String truncate(String error) {
        String value = error == null || error.isBlank() ? "unknown delivery failure" : error;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    @PreDestroy
    void stop() {
        triggerExecutor.shutdownNow();
        deliveryExecutor.shutdownNow();
    }
}
