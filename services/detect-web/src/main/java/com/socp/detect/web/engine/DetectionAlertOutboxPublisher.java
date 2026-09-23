package com.socp.detect.web.engine;

import com.socp.platform.data.outbox.OutboxRetryPolicy;
import com.socp.detect.web.config.DetectRuntimeRole;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retries the durable Detection -> Alert Web hand-off.
 *
 * <p>Conditional claims and unique claim tokens prevent an expired publisher
 * from overwriting a newer owner's state, including after operator requeue.
 * Lease expiry can still overlap external deliveries; Alert Web is idempotent
 * by tenant + sourceAlertId. Stale PROCESSING rows return to the correct stage
 * or become DEAD at the retry limit.
 * The optional secondary-analysis event is a second stage; it never causes a failed
 * Alert Web request to be retried as a new alert.</p>
 */
@Component
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
public class DetectionAlertOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(DetectionAlertOutboxPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final Duration DISCARDED_RETENTION = Duration.ofDays(30);
    private static final int DEFAULT_MAX_DRAIN_ROUNDS = 64;
    private static final long DEFAULT_MAX_DRAIN_DURATION_MS = 2_000L;
    private static final int DEFAULT_CLEANUP_BATCH_SIZE = 1_000;
    private static final int DEFAULT_CLEANUP_MAX_BATCHES = 10;

    private final DetectionAlertOutboxRepository repository;
    private final AlertClient alertClient;
    private final AlarmKafkaProducer alarmProducer;
    private final DetectionPerformanceMetrics performanceMetrics;
    private final ExecutorService deliveryExecutor;
    private final Semaphore deliveryPermits;
    private final int maxAttempts;
    private final long retentionMs;
    private final int maxDrainRounds;
    private final long maxDrainDurationNanos;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;

    @Value("${socp.detect.output-mode:primary}")
    private String outputMode = "primary";

    @org.springframework.beans.factory.annotation.Autowired
    public DetectionAlertOutboxPublisher(DetectionAlertOutboxRepository repository,
                                         AlertClient alertClient,
                                         AlarmKafkaProducer alarmProducer,
                                         DetectionPerformanceMetrics performanceMetrics,
                                         @Value("${socp.detect.alert-outbox.delivery-concurrency:2}")
                                         int deliveryConcurrency,
                                         @Value("${socp.detect.alert-outbox.max-attempts:12}") int maxAttempts,
                                         @Value("${socp.detect.alert-outbox.retention-ms:2592000000}") long retentionMs,
                                         @Value("${socp.detect.alert-outbox.max-drain-rounds:64}") int maxDrainRounds,
                                         @Value("${socp.detect.alert-outbox.max-drain-duration-ms:2000}")
                                         long maxDrainDurationMs,
                                         @Value("${socp.detect.alert-outbox.cleanup-batch-size:1000}") int cleanupBatchSize,
                                         @Value("${socp.detect.alert-outbox.cleanup-max-batches:10}") int cleanupMaxBatches) {
        this.repository = repository;
        this.alertClient = alertClient;
        this.alarmProducer = alarmProducer;
        this.performanceMetrics = performanceMetrics;
        int concurrency = Math.max(1, Math.min(32, deliveryConcurrency));
        this.deliveryExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("detection-alert-delivery-", 0).factory());
        this.deliveryPermits = new Semaphore(concurrency);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retentionMs = Math.max(Duration.ofMinutes(1).toMillis(), retentionMs);
        // Two delivery stages share one window; reserve at least one round for each.
        this.maxDrainRounds = Math.max(2, Math.min(1_000, maxDrainRounds));
        this.maxDrainDurationNanos = Duration.ofMillis(
                Math.max(10L, Math.min(60_000L, maxDrainDurationMs))).toNanos();
        this.cleanupBatchSize = Math.max(1, Math.min(10_000, cleanupBatchSize));
        this.cleanupMaxBatches = Math.max(1, Math.min(100, cleanupMaxBatches));
    }

    public DetectionAlertOutboxPublisher(DetectionAlertOutboxRepository repository,
                                         AlertClient alertClient,
                                         AlarmKafkaProducer alarmProducer,
                                         DetectionPerformanceMetrics performanceMetrics,
                                         int deliveryConcurrency,
                                         int maxAttempts,
                                         long retentionMs) {
        this(repository, alertClient, alarmProducer, performanceMetrics, deliveryConcurrency,
                maxAttempts, retentionMs, DEFAULT_MAX_DRAIN_ROUNDS, DEFAULT_MAX_DRAIN_DURATION_MS,
                DEFAULT_CLEANUP_BATCH_SIZE, DEFAULT_CLEANUP_MAX_BATCHES);
    }

    public DetectionAlertOutboxPublisher(DetectionAlertOutboxRepository repository,
                                         AlertClient alertClient,
                                         AlarmKafkaProducer alarmProducer,
                                         DetectionPerformanceMetrics performanceMetrics,
                                         int deliveryConcurrency,
                                         int maxAttempts,
                                         long retentionMs,
                                         int maxDrainRounds,
                                         long maxDrainDurationMs) {
        this(repository, alertClient, alarmProducer, performanceMetrics, deliveryConcurrency,
                maxAttempts, retentionMs, maxDrainRounds, maxDrainDurationMs,
                DEFAULT_CLEANUP_BATCH_SIZE, DEFAULT_CLEANUP_MAX_BATCHES);
    }

    DetectionAlertOutboxPublisher(DetectionAlertOutboxRepository repository,
                                  AlertClient alertClient,
                                  AlarmKafkaProducer alarmProducer,
                                  DetectionPerformanceMetrics performanceMetrics,
                                  int deliveryConcurrency) {
        this(repository, alertClient, alarmProducer, performanceMetrics, deliveryConcurrency,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS);
    }

    /** Unit-test/source compatibility constructor. */
    public DetectionAlertOutboxPublisher(DetectionAlertOutboxRepository repository,
                                         AlertClient alertClient,
                                         AlarmKafkaProducer alarmProducer) {
        this(repository, alertClient, alarmProducer, null, 1,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS);
    }

    private final AtomicBoolean activeTrigger = new AtomicBoolean(false);
    private final AtomicBoolean activeDrain = new AtomicBoolean(false);

    /** Triggers an immediate asynchronous outbox publish cycle on alert enqueue. */
    public void triggerAsync() {
        if (!formalOutput()) return;
        if (activeTrigger.compareAndSet(false, true)) {
            deliveryExecutor.execute(() -> {
                try {
                    // This direct executor path does not pass through the
                    // scheduled-job aspect, so establish explicit system scope.
                    TenantContext.runAsSystem(this::publishDue);
                } finally {
                    activeTrigger.set(false);
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${socp.detect.alert-outbox.poll-interval-ms:1000}",
            initialDelayString = "${socp.detect.alert-outbox.initial-delay-ms:1000}")
    @TenantSystemJob
    public void publishDue() {
        if (!formalOutput() || !activeDrain.compareAndSet(false, true)) return;
        long started = System.nanoTime();
        int rounds = 0;
        try {
            recoverStaleClaims();
            int exhausted = repository.markExhaustedBatch(maxAttempts, Instant.now(), 100);
            if (exhausted > 0) {
                log.error("Detection alert outbox rows moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            int pendingRoundBudget = (maxDrainRounds + 1) / 2;
            long pendingDeadline = started + maxDrainDurationNanos / 2;
            long overallDeadline = started + maxDrainDurationNanos;
            rounds += publishStage("PENDING", pendingRoundBudget, pendingDeadline);
            rounds += publishStage("DELIVERED", maxDrainRounds - rounds, overallDeadline);
        } catch (Exception ex) {
            log.warn("Detection alert outbox scan failed; next scan will retry: {}", ex.getMessage());
        } finally {
            activeDrain.set(false);
            if (performanceMetrics != null) {
                performanceMetrics.outboxDrain("alert_delivery", rounds, System.nanoTime() - started);
                refreshBacklog();
            }
        }
    }

    private void refreshBacklog() {
        try {
            performanceMetrics.outboxBacklog("alert_delivery",
                    repository.countByStatus("PENDING") + repository.countByStatus("DELIVERED"),
                    oldest(repository.findOldestCreatedAtByStatus("PENDING"),
                            repository.findOldestCreatedAtByStatus("DELIVERED")),
                    repository.countByStatus("DEAD"),
                    repository.findOldestUpdatedAtByStatus("DEAD"));
        } catch (RuntimeException failure) {
            log.warn("Detection alert outbox backlog metrics deferred: {}", failure.getMessage());
        }
    }

    private static Instant oldest(Instant first, Instant second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.isBefore(second) ? first : second;
    }

    private int publishStage(String stage, int remainingRounds, long deadlineNanos) {
        int rounds = 0;
        while (rounds < remainingRounds && System.nanoTime() < deadlineNanos) {
            List<DetectionAlertOutboxEntity> due = repository
                    .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(stage, Instant.now());
            if (due.isEmpty()) break;
            rounds++;
            List<CompletableFuture<Void>> deliveries = new ArrayList<>();
            for (DetectionAlertOutboxEntity event : due) {
                // Wait for capacity before starting the database lease. A slow
                // destination must not expire claims queued behind other sends.
                if (!acquireBefore(deadlineNanos)) break;
                if (!claim(event, stage)) {
                    deliveryPermits.release();
                    continue;
                }
                try {
                    deliveries.add(CompletableFuture.runAsync(() -> {
                        try (TenantContext.Scope ignored = TenantContext.open(event.getTenantId())) {
                            if ("PENDING".equals(stage)) {
                                Instant claimedAt = performanceMetrics == null
                                        ? Instant.now() : performanceMetrics.outboxClaimed(event);
                                deliverAndPublish(event, claimedAt);
                            } else {
                                if (performanceMetrics != null) {
                                    performanceMetrics.outboxStateTransaction("original_claim");
                                }
                                publishOriginalAlarm(event);
                            }
                        } finally {
                            deliveryPermits.release();
                        }
                    }, deliveryExecutor));
                } catch (RuntimeException rejected) {
                    deliveryPermits.release();
                    withTenant(event, () -> {
                        fail(event, "delivery executor unavailable", stage);
                        return null;
                    });
                    break;
                }
            }
            CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();
            if (due.size() < 100) break;
        }
        return rounds;
    }

    private boolean acquireBefore(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return false;
        try {
            if (!deliveryPermits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) return false;
            if (System.nanoTime() >= deadlineNanos) {
                deliveryPermits.release();
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean claim(DetectionAlertOutboxEntity event, String expectedStage) {
        try {
            String token = UUID.randomUUID().toString();
            boolean claimed = repository.claim(event.getAlertId(), expectedStage, event.getAttempts(),
                    token, Instant.now(), maxAttempts) == 1;
            if (claimed) {
                event.setAttempts(event.getAttempts() + 1);
                event.setClaimToken(token);
            }
            return claimed;
        } catch (Exception ex) {
            log.warn("Unable to claim Detection alert outbox alertId={}: {}", event.getAlertId(), ex.getMessage());
            return false;
        }
    }

    private void deliverAndPublish(DetectionAlertOutboxEntity event, Instant claimedAt) {
        ServiceCall call;
        try {
            String deliveryPayload = payloadWithClaimedAt(event.getPayload(), claimedAt);
            call = withTenant(event, () -> alertClient.forwardAlarm(deliveryPayload));
        } catch (Exception ex) {
            if (performanceMetrics != null) performanceMetrics.alertDeliveryFailed(event.getAlertId());
            fail(event, "alert-web exception: " + ex.getMessage(), "PENDING");
            return;
        }
        if (call == null || !call.ok()) {
            if (performanceMetrics != null) performanceMetrics.alertDeliveryFailed(event.getAlertId());
            fail(event, "alert-web: " + (call == null ? "empty service response" : call.failureReason()), "PENDING");
            return;
        }
        if (performanceMetrics != null) {
            performanceMetrics.alertAcknowledged(event.getAlertId(), call);
        }
        Instant now = Instant.now();
        event.setStatus("DELIVERED");
        event.setDeliveredAt(now);
        event.setNextAttemptAt(now);
        event.setUpdatedAt(now);
        event.setLastError(null);
        // Keep the database row PROCESSING while the optional original-alarm
        // publication completes. The happy path now needs only claim + final
        // PUBLISHED transactions. If this process crashes in between, stale
        // claim recovery returns the row to PENDING and Alert Web's
        // sourceAlertId idempotency safely absorbs the repeated HTTP request.
        // A failed second stage persists DELIVERED and resumes there without
        // repeating the already successful Alert Web request.
        publishOriginalAlarm(event);
    }

    private void publishOriginalAlarm(DetectionAlertOutboxEntity event) {
        try {
            Map<String, Object> payload = MAPPER.readValue(event.getPayload(), MAP);
            // Rejoin the trace recorded when the row was enqueued; this thread
            // has no live span of its own to inherit.
            boolean published = alarmProducer.sendAndAwait(payload, event.getAlertId(),
                    event.getTraceparent());
            if (!published) {
                fail(event, "socp-alarm-original publish failed", "DELIVERED");
                return;
            }
            Instant now = Instant.now();
            if (repository.markPublished(event.getAlertId(), event.getClaimToken(), event.getDeliveredAt(), now) == 1) {
                event.setStatus("PUBLISHED");
                if (performanceMetrics != null) performanceMetrics.outboxStateTransaction("published_state");
            } else {
                log.warn("Detection alert outbox acknowledgement lost claim alertId={}", event.getAlertId());
            }
        } catch (Exception ex) {
            fail(event, "original alarm exception: " + ex.getMessage(), "DELIVERED");
        }
    }

    void recoverStaleClaims() {
        Instant now = Instant.now();
        int recovered = repository.recoverStaleBatch(now.minus(Duration.ofMinutes(2)), now, maxAttempts, 100);
        if (recovered > 0) lifecycle("recovered", recovered);
    }

    void fail(DetectionAlertOutboxEntity event, String reason, String stage) {
        int attempts = event.getAttempts();
        Instant now = Instant.now();
        var decision = OutboxRetryPolicy.afterClaim(attempts, maxAttempts, now, reason, 60);
        String status = decision.exhausted() ? "DEAD" : stage;
        Instant next = decision.exhausted() ? now : decision.nextAttemptAt();
        if (repository.markFailed(event.getAlertId(), event.getClaimToken(), status, event.getDeliveredAt(),
                next, decision.error(), now) != 1) {
            log.warn("Detection alert outbox failure lost claim alertId={}", event.getAlertId());
            return;
        }
        event.setStatus(status);
        event.setNextAttemptAt(next);
        event.setLastError(decision.error());
        if (decision.exhausted()) {
            if (performanceMetrics != null) performanceMetrics.outboxStateTransaction("dead_state");
            lifecycle("dead", 1);
            log.error("Detection alert outbox moved to DEAD alertId={} stage={} attempts={} reason={}",
                    event.getAlertId(), stage, attempts, event.getLastError());
            return;
        }
        if (performanceMetrics != null) performanceMetrics.outboxStateTransaction("retry_state");
        lifecycle("retry", 1);
        log.warn("Detection alert outbox retry scheduled alertId={} stage={} attempts={} next={} reason={}",
                event.getAlertId(), stage, attempts, event.getNextAttemptAt(), event.getLastError());
    }

    @Scheduled(fixedDelayString = "${socp.detect.alert-outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.detect.alert-outbox.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupPublished() {
        try {
            int removed = 0;
            Instant cutoff = Instant.now().minusMillis(retentionMs);
            for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                int deleted = repository.deletePublishedBatchBefore(cutoff, cleanupBatchSize);
                removed += deleted;
                if (deleted < cleanupBatchSize) break;
            }
            if (removed > 0) {
                log.info("Removed retained Detection alert outbox rows count={}", removed);
                lifecycle("cleaned", removed);
            }
            int discarded = 0;
            Instant discardedCutoff = Instant.now().minus(DISCARDED_RETENTION);
            for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                int deleted = repository.deleteDiscardedBatchBefore(discardedCutoff, cleanupBatchSize);
                discarded += deleted;
                if (deleted < cleanupBatchSize) break;
            }
            if (discarded > 0) {
                log.info("Removed explicitly discarded Detection alert outbox rows count={}", discarded);
                lifecycle("discarded_cleaned", discarded);
            }
        } catch (RuntimeException failure) {
            log.warn("Detection alert outbox retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private boolean formalOutput() {
        return outputMode == null || outputMode.isBlank()
                || "primary".equalsIgnoreCase(outputMode.trim());
    }

    private void lifecycle(String outcome, int count) {
        if (performanceMetrics != null) performanceMetrics.outboxLifecycle("alert_delivery", outcome, count);
    }

    private static <T> T withTenant(DetectionAlertOutboxEntity event,
                                    java.util.function.Supplier<T> action) {
        return TenantContext.callWith(event.getTenantId(), action);
    }

    @PreDestroy
    void stopDeliveryExecutor() {
        deliveryExecutor.shutdownNow();
    }

    private static String payloadWithClaimedAt(String payload, Instant claimedAt) {
        try {
            Map<String, Object> values = MAPPER.readValue(payload, MAP);
            values.put("detectionOutboxClaimedAt", claimedAt.toString());
            return MAPPER.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to add alert delivery timestamp", ex);
        }
    }
}
