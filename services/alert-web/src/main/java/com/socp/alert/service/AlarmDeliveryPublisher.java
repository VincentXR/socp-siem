package com.socp.alert.service;

import com.socp.platform.data.outbox.OutboxRetryPolicy;

import com.socp.alert.config.AlertDeliveryProperties;
import com.socp.alert.domain.Alarm;
import com.socp.alert.domain.AlarmDelivery;
import com.socp.alert.domain.AlarmDeliveryDestination;
import com.socp.alert.persistence.repository.AlarmDeliveryRepository;


import com.socp.platform.client.service.IncidentClient;
import com.socp.platform.client.service.NotifyClient;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.service.SoarClient;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class AlarmDeliveryPublisher {

    private static final Logger log = LoggerFactory.getLogger(AlarmDeliveryPublisher.class);
    private static final int MAX_ERROR_LENGTH = 1024;
    private static final int DEFAULT_MAX_ATTEMPTS = 12;
    private static final long DEFAULT_RETENTION_MS = Duration.ofDays(30).toMillis();
    private static final Duration DISCARDED_RETENTION = Duration.ofDays(30);
    private static final int DEFAULT_MAX_DRAIN_ROUNDS = 64;
    private static final long DEFAULT_MAX_DRAIN_DURATION_MS = 2_000L;
    private static final int DEFAULT_CLEANUP_BATCH_SIZE = 1_000;
    private static final int DEFAULT_CLEANUP_MAX_BATCHES = 10;

    private final AlarmDeliveryRepository repository;
    private final CkReporter ckReporter;
    private final NotifyClient notifyClient;
    private final IncidentClient incidentClient;
    private final SoarClient soarClient;
    private final AlertPerformanceMetrics performanceMetrics;
    private final ExecutorService executor;
    private final ExecutorService triggerExecutor;
    private final int maxAttempts;
    private final long retentionMs;
    private final int maxDrainRounds;
    private final long maxDrainDurationNanos;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatches;
    private Instant nextRecoveryAt = Instant.EPOCH;

    @Autowired
    public AlarmDeliveryPublisher(AlarmDeliveryRepository repository, CkReporter ckReporter,
                                  NotifyClient notifyClient, IncidentClient incidentClient,
                                  SoarClient soarClient, AlertPerformanceMetrics performanceMetrics,
                                  AlertDeliveryProperties properties) {
        this(repository, ckReporter, notifyClient, incidentClient, soarClient, performanceMetrics,
                properties.getConcurrency(), properties.getMaxAttempts(), properties.getRetentionMs(),
                properties.getMaxDrainRounds(), properties.getMaxDrainDurationMs(),
                properties.getCleanupBatchSize(), properties.getCleanupMaxBatches());
    }

    public AlarmDeliveryPublisher(AlarmDeliveryRepository repository, CkReporter ckReporter,
                                  NotifyClient notifyClient, IncidentClient incidentClient,
                                  SoarClient soarClient, AlertPerformanceMetrics performanceMetrics,
                                  int concurrency, int maxAttempts, long retentionMs) {
        this(repository, ckReporter, notifyClient, incidentClient, soarClient, performanceMetrics,
                concurrency, maxAttempts, retentionMs,
                DEFAULT_MAX_DRAIN_ROUNDS, DEFAULT_MAX_DRAIN_DURATION_MS,
                DEFAULT_CLEANUP_BATCH_SIZE, DEFAULT_CLEANUP_MAX_BATCHES);
    }

    public AlarmDeliveryPublisher(AlarmDeliveryRepository repository, CkReporter ckReporter,
                                  NotifyClient notifyClient, IncidentClient incidentClient,
                                  SoarClient soarClient, AlertPerformanceMetrics performanceMetrics,
                                  int concurrency, int maxAttempts, long retentionMs,
                                  int maxDrainRounds, long maxDrainDurationMs) {
        this(repository, ckReporter, notifyClient, incidentClient, soarClient, performanceMetrics,
                concurrency, maxAttempts, retentionMs,
                maxDrainRounds, maxDrainDurationMs,
                DEFAULT_CLEANUP_BATCH_SIZE, DEFAULT_CLEANUP_MAX_BATCHES);
    }

    public AlarmDeliveryPublisher(AlarmDeliveryRepository repository, CkReporter ckReporter,
                                  NotifyClient notifyClient, IncidentClient incidentClient,
                                  SoarClient soarClient, AlertPerformanceMetrics performanceMetrics,
                                  int concurrency, int maxAttempts, long retentionMs,
                                  int maxDrainRounds, long maxDrainDurationMs,
                                  int cleanupBatchSize, int cleanupMaxBatches) {
        this.repository = repository;
        this.ckReporter = ckReporter;
        this.notifyClient = notifyClient;
        this.incidentClient = incidentClient;
        this.soarClient = soarClient;
        this.performanceMetrics = performanceMetrics;
        int bounded = Math.max(1, Math.min(32, concurrency));
        this.executor = Executors.newFixedThreadPool(bounded,
                Thread.ofVirtual().name("alarm-delivery-", 0).factory());
        this.triggerExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("alarm-delivery-trigger-", 0).factory());
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retentionMs = Math.max(Duration.ofMinutes(1).toMillis(), retentionMs);
        this.maxDrainRounds = Math.max(1, Math.min(1_000, maxDrainRounds));
        this.maxDrainDurationNanos = Duration.ofMillis(
                Math.max(10L, Math.min(60_000L, maxDrainDurationMs))).toNanos();
        this.cleanupBatchSize = Math.max(1, Math.min(10_000, cleanupBatchSize));
        this.cleanupMaxBatches = Math.max(1, Math.min(100, cleanupMaxBatches));
    }

    private final java.util.concurrent.atomic.AtomicBoolean activeTrigger = new java.util.concurrent.atomic.AtomicBoolean(false);

    AlarmDeliveryPublisher(AlarmDeliveryRepository repository, CkReporter ckReporter,
                           NotifyClient notifyClient, IncidentClient incidentClient, SoarClient soarClient) {
        this(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS);
    }

    /** Triggers an immediate asynchronous downstream delivery cycle on transaction commit. */
    public void triggerAsync() {
        if (activeTrigger.compareAndSet(false, true)) {
            triggerExecutor.execute(() -> {
                try {
                    // Keep the executor path explicit: direct self-invocation
                    // does not pass through the scheduled-job system scope.
                    TenantContext.runAsSystem(this::publish);
                } finally {
                    activeTrigger.set(false);
                }
            });
        }
    }

    @Scheduled(fixedDelayString = "${socp.alert.delivery.poll-interval-ms:1000}",
            initialDelayString = "${socp.alert.delivery.initial-delay-ms:1000}")
    @TenantSystemJob
    public void publish() {
        long started = System.nanoTime();
        int rounds = 0;
        try {
            Instant now = Instant.now();
            recoverStaleIfDue(now);
            int exhausted = repository.markExhausted(maxAttempts, "retry limit reached", now);
            if (exhausted > 0) {
                log.error("Alarm deliveries moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            while (rounds < maxDrainRounds && System.nanoTime() - started < maxDrainDurationNanos) {
                now = Instant.now();
                List<AlarmDelivery> pending = repository
                        .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
                if (pending.isEmpty()) break;
                rounds++;
                List<CompletableFuture<Void>> work = pending.stream()
                        .map(delivery -> CompletableFuture.runAsync(() -> deliver(delivery), executor))
                        .toList();
                CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
                if (pending.size() < 100) break;
            }
        } catch (RuntimeException failure) {
            log.warn("Alarm delivery scan failed; next scan will retry: {}", failure.getMessage());
        } finally {
            if (performanceMetrics != null) {
                performanceMetrics.outboxDrain("alarm_delivery", rounds, System.nanoTime() - started);
                refreshBacklog();
            }
        }
    }

    private void refreshBacklog() {
        try {
            performanceMetrics.outboxBacklog("alarm_delivery",
                    repository.countByStatus("PENDING"),
                    repository.findOldestCreatedAtByStatus("PENDING"),
                    repository.countByStatus("DEAD"),
                    repository.findOldestUpdatedAtByStatus("DEAD"));
        } catch (RuntimeException failure) {
            log.warn("Alarm delivery backlog metrics deferred: {}", failure.getMessage());
        }
    }

    private void recoverStaleIfDue(Instant now) {
        if (now.isBefore(nextRecoveryAt)) return;
        int recovered = repository.recoverStale(now.minus(Duration.ofMinutes(2)), now);
        if (recovered > 0) {
            log.warn("Recovered stale alarm deliveries count={}", recovered);
            lifecycle("recovered", recovered);
        }
        nextRecoveryAt = now.plus(Duration.ofSeconds(30));
    }

    private void deliver(AlarmDelivery delivery) {
        boolean claimed = false;
        String previousTrace = MDC.get("traceId");
        try (TenantContext.Scope ignored = TenantContext.open(delivery.getTenantId())) {
            Instant now = Instant.now();
            if (repository.claim(delivery.getId(), now, maxAttempts) != 1) return;
            claimed = true;
            if (delivery.getTraceId() != null) MDC.put("traceId", delivery.getTraceId());
            DeliveryResult result = dispatch(delivery);
            if (result.delivered()) {
                if (repository.markDelivered(delivery.getId(), Instant.now()) != 1) {
                    log.warn("Alarm delivery state changed before acknowledgement id={}", delivery.getId());
                }
            } else {
                scheduleRetry(delivery, result.error());
            }
        } catch (RuntimeException failure) {
            if (claimed) scheduleRetry(delivery, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            if (previousTrace == null) MDC.remove("traceId");
            else MDC.put("traceId", previousTrace);
        }
    }

    private DeliveryResult dispatch(AlarmDelivery delivery) {
        AlarmDeliveryDestination destination = AlarmDeliveryDestination.valueOf(delivery.getDestination());
        if (destination == AlarmDeliveryDestination.CLICKHOUSE) {
            try {
                Map<String, Object> payload = AlarmPayloadCodec.read(delivery.getPayload());
                return ckReporter.reportAlarmAndAwait(AlarmPayloadCodec.toAlarm(payload))
                        ? DeliveryResult.success() : DeliveryResult.failure("ClickHouse rejected alarm");
            } catch (Exception failure) {
                return DeliveryResult.failure("invalid ClickHouse payload: " + failure.getMessage());
            }
        }
        ServiceCall call = switch (destination) {
            case NOTIFY -> notifyClient.notifyAlert(delivery.getPayload());
            case INCIDENT -> incidentClient.createFromAlarm(delivery.getPayload());
            case SOAR -> soarClient.evaluate(delivery.getPayload());
            case CLICKHOUSE -> throw new IllegalStateException("unreachable destination");
        };
        if (call == null) return DeliveryResult.failure(destination + " returned no result");
        return call.ok() ? DeliveryResult.success() : DeliveryResult.failure(call.failureReason());
    }

    private void scheduleRetry(AlarmDelivery delivery, String error) {
        Instant now = Instant.now();
        var decision = OutboxRetryPolicy.afterClaim(
                delivery.getAttempts() + 1, maxAttempts, now, error, 900);
        try {
            if (decision.exhausted()) {
                if (repository.markDead(delivery.getId(), decision.error(), now) == 1) {
                    log.error("Alarm delivery moved to DEAD alarmId={} destination={} attempts={} reason={}",
                            delivery.getAlarmId(), delivery.getDestination(),
                            decision.attempts(), decision.error());
                    lifecycle("dead", 1);
                }
                return;
            }
            if (repository.scheduleRetry(delivery.getId(), decision.nextAttemptAt(), decision.error(), now) == 1) {
                log.warn("Alarm delivery retry scheduled alarmId={} destination={} attempts={} next={} reason={}",
                        delivery.getAlarmId(), delivery.getDestination(), decision.attempts(),
                        decision.nextAttemptAt(), decision.error());
                lifecycle("retry", 1);
            }
        } catch (RuntimeException stateFailure) {
            // The claim stays PROCESSING and will be recovered safely if this write failed.
            log.warn("Alarm delivery retry state update deferred id={}: {}",
                    delivery.getId(), stateFailure.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${socp.alert.delivery.cleanup-interval-ms:3600000}",
            initialDelayString = "${socp.alert.delivery.cleanup-initial-delay-ms:60000}")
    @TenantSystemJob
    void cleanupDelivered() {
        try {
            int removed = 0;
            Instant cutoff = Instant.now().minusMillis(retentionMs);
            for (int batch = 0; batch < cleanupMaxBatches; batch++) {
                int deleted = repository.deleteDeliveredBatchBefore(cutoff, cleanupBatchSize);
                removed += deleted;
                if (deleted < cleanupBatchSize) break;
            }
            if (removed > 0) {
                log.info("Removed retained alarm delivery rows count={}", removed);
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
                log.info("Removed explicitly discarded alarm delivery rows count={}", discarded);
                lifecycle("discarded_cleaned", discarded);
            }
        } catch (RuntimeException failure) {
            log.warn("Alarm delivery retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private void lifecycle(String outcome, int count) {
        if (performanceMetrics != null) performanceMetrics.outboxLifecycle("alarm_delivery", outcome, count);
    }

    @PreDestroy
    void stop() {
        triggerExecutor.shutdownNow();
        executor.shutdownNow();
    }

    private record DeliveryResult(boolean delivered, String error) {
        static DeliveryResult success() { return new DeliveryResult(true, null); }
        static DeliveryResult failure(String error) { return new DeliveryResult(false, error); }
    }
}
