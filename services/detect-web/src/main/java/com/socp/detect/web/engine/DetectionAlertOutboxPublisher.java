package com.socp.detect.web.engine;

import com.socp.platform.data.outbox.OutboxRetryPolicy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.persistence.repository.DetectionAlertOutboxRepository;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.platform.client.service.AlertClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.tenant.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Retries the durable Detection -> Alert Web hand-off.
 *
 * <p>Claiming is an optimistic database update, so multiple detect-web
 * instances sharing a database cannot publish the same outbox row at the same
 * time.  A stale PROCESSING row is returned to the correct stage after a
 * publisher crash.  Alert Web itself is idempotent by tenant + sourceAlertId.
 * The optional detect-model event is a second stage; it never causes a failed
 * Alert Web request to be retried as a new alert.</p>
 */
@Component
public class DetectionAlertOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(DetectionAlertOutboxPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();

    private final DetectionAlertOutboxRepository repository;
    private final AlertClient alertClient;
    private final AlarmKafkaProducer alarmProducer;
    private final DetectionPerformanceMetrics performanceMetrics;
    private final ExecutorService deliveryExecutor;
    private final Semaphore deliveryPermits;
    private final int maxAttempts;
    private final long retentionMs;

    @org.springframework.beans.factory.annotation.Autowired
    public DetectionAlertOutboxPublisher(DetectionAlertOutboxRepository repository,
                                         AlertClient alertClient,
                                         AlarmKafkaProducer alarmProducer,
                                         DetectionPerformanceMetrics performanceMetrics,
                                         @Value("${socp.detect.alert-outbox.delivery-concurrency:2}")
                                         int deliveryConcurrency,
                                         @Value("${socp.detect.alert-outbox.max-attempts:12}") int maxAttempts,
                                         @Value("${socp.detect.alert-outbox.retention-ms:2592000000}") long retentionMs) {
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
        this.repository = repository;
        this.alertClient = alertClient;
        this.alarmProducer = alarmProducer;
        this.performanceMetrics = null;
        this.deliveryExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("detection-alert-delivery-test-", 0).factory());
        this.deliveryPermits = new Semaphore(1);
        this.maxAttempts = DEFAULT_MAX_ATTEMPTS;
        this.retentionMs = DEFAULT_RETENTION_MS;
    }

    private final java.util.concurrent.atomic.AtomicBoolean activeTrigger = new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Triggers an immediate asynchronous outbox publish cycle on alert enqueue. */
    public void triggerAsync() {
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
    public void publishDue() {
        try {
            recoverStaleClaims();
            int exhausted = repository.markExhausted(maxAttempts, "retry limit reached", Instant.now());
            if (exhausted > 0) {
                log.error("Detection alert outbox rows moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            publishStage("PENDING");
            publishStage("DELIVERED");
        } catch (Exception ex) {
            log.warn("Detection alert outbox scan failed; next scan will retry: {}", ex.getMessage());
        }
    }

    private void publishStage(String stage) {
        List<DetectionAlertOutboxEntity> due = repository
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(stage, Instant.now());
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        for (DetectionAlertOutboxEntity event : due) {
            if (!claim(event, stage)) continue;
            deliveries.add(CompletableFuture.runAsync(() -> {
                try (TenantContext.Scope ignored = TenantContext.open(event.getTenantId())) {
                    boolean acquired = false;
                    try {
                        deliveryPermits.acquire();
                        acquired = true;
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
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        fail(event, "delivery interrupted", stage);
                    } finally {
                        if (acquired) deliveryPermits.release();
                    }
                }
            }, deliveryExecutor));
        }
        CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new)).join();
    }

    private boolean claim(DetectionAlertOutboxEntity event, String expectedStage) {
        try {
            boolean claimed = repository.claim(event.getAlertId(), expectedStage, Instant.now(), maxAttempts) == 1;
            if (claimed) {
                // The repository increments atomically. Keep the detached object
                // aligned so a subsequent save cannot overwrite that increment.
                event.setAttempts(event.getAttempts() + 1);
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
            boolean published = alarmProducer.sendAndAwait(payload, event.getAlertId());
            if (!published) {
                fail(event, "socp-alarm-original publish failed", "DELIVERED");
                return;
            }
            Instant now = Instant.now();
            event.setStatus("PUBLISHED");
            event.setPublishedAt(now);
            event.setNextAttemptAt(now);
            event.setUpdatedAt(now);
            event.setLastError(null);
            save(event);
            if (performanceMetrics != null) performanceMetrics.outboxStateTransaction("published_state");
        } catch (Exception ex) {
            fail(event, "original alarm exception: " + ex.getMessage(), "DELIVERED");
        }
    }

    @Transactional
    void recoverStaleClaims() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(2));
        for (DetectionAlertOutboxEntity event : repository.findByStatusAndUpdatedAtBefore("PROCESSING", cutoff)) {
            event.setStatus(event.alertDelivered() ? "DELIVERED" : "PENDING");
            event.setNextAttemptAt(Instant.now());
            event.setUpdatedAt(Instant.now());
            repository.save(event);
            lifecycle("recovered", 1);
        }
    }

    @Transactional
    void fail(DetectionAlertOutboxEntity event, String reason, String stage) {
        int attempts = event.getAttempts();
        Instant now = Instant.now();
        var decision = OutboxRetryPolicy.afterClaim(attempts, maxAttempts, now, reason, 60);
        event.setAttempts(attempts);
        event.setUpdatedAt(now);
        event.setLastError(decision.error());
        if (decision.exhausted()) {
            event.setStatus("DEAD");
            event.setNextAttemptAt(now);
            repository.save(event);
            if (performanceMetrics != null) performanceMetrics.outboxStateTransaction("dead_state");
            lifecycle("dead", 1);
            log.error("Detection alert outbox moved to DEAD alertId={} stage={} attempts={} reason={}",
                    event.getAlertId(), stage, attempts, event.getLastError());
            return;
        }
        event.setStatus(stage);
        event.setNextAttemptAt(decision.nextAttemptAt());
        repository.save(event);
        if (performanceMetrics != null) performanceMetrics.outboxStateTransaction("retry_state");
        lifecycle("retry", 1);
        log.warn("Detection alert outbox retry scheduled alertId={} stage={} attempts={} next={} reason={}",
                event.getAlertId(), stage, attempts, event.getNextAttemptAt(), event.getLastError());
    }

    @Transactional
    void save(DetectionAlertOutboxEntity event) {
        repository.save(event);
    }

    @Scheduled(fixedDelayString = "${socp.detect.alert-outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.detect.alert-outbox.cleanup-initial-delay-ms:60000}")
    void cleanupPublished() {
        try {
            int removed = repository.deletePublishedBefore(Instant.now().minusMillis(retentionMs));
            if (removed > 0) {
                log.info("Removed retained Detection alert outbox rows count={}", removed);
                lifecycle("cleaned", removed);
            }
        } catch (RuntimeException failure) {
            log.warn("Detection alert outbox retention cleanup deferred: {}", failure.getMessage());
        }
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
