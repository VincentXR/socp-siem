package com.socp.alert;

import com.socp.platform.client.IncidentClient;
import com.socp.platform.client.NotifyClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.client.SoarClient;
import com.socp.platform.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    private final AlarmDeliveryRepository repository;
    private final CkReporter ckReporter;
    private final NotifyClient notifyClient;
    private final IncidentClient incidentClient;
    private final SoarClient soarClient;
    private final AlertPerformanceMetrics performanceMetrics;
    private final ExecutorService executor;
    private final int maxAttempts;
    private final long retentionMs;
    private Instant nextRecoveryAt = Instant.EPOCH;

    @Autowired
    public AlarmDeliveryPublisher(AlarmDeliveryRepository repository, CkReporter ckReporter,
                                  NotifyClient notifyClient, IncidentClient incidentClient,
                                  SoarClient soarClient, AlertPerformanceMetrics performanceMetrics,
                                  @Value("${socp.alert.delivery.concurrency:8}") int concurrency,
                                  @Value("${socp.alert.delivery.max-attempts:12}") int maxAttempts,
                                  @Value("${socp.alert.delivery.retention-ms:2592000000}") long retentionMs) {
        this.repository = repository;
        this.ckReporter = ckReporter;
        this.notifyClient = notifyClient;
        this.incidentClient = incidentClient;
        this.soarClient = soarClient;
        this.performanceMetrics = performanceMetrics;
        int bounded = Math.max(1, Math.min(32, concurrency));
        this.executor = Executors.newFixedThreadPool(bounded,
                Thread.ofVirtual().name("alarm-delivery-", 0).factory());
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retentionMs = Math.max(Duration.ofMinutes(1).toMillis(), retentionMs);
    }

    AlarmDeliveryPublisher(AlarmDeliveryRepository repository, CkReporter ckReporter,
                           NotifyClient notifyClient, IncidentClient incidentClient, SoarClient soarClient) {
        this(repository, ckReporter, notifyClient, incidentClient, soarClient,
                null, 1, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETENTION_MS);
    }

    @Scheduled(fixedDelayString = "${socp.alert.delivery.poll-interval-ms:1000}",
            initialDelayString = "${socp.alert.delivery.initial-delay-ms:1000}")
    public void publish() {
        try {
            Instant now = Instant.now();
            recoverStaleIfDue(now);
            int exhausted = repository.markExhausted(maxAttempts, "retry limit reached", now);
            if (exhausted > 0) {
                log.error("Alarm deliveries moved to DEAD after retry limit count={}", exhausted);
                lifecycle("dead", exhausted);
            }
            List<AlarmDelivery> pending = repository
                    .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc("PENDING", now);
            List<CompletableFuture<Void>> work = pending.stream()
                    .map(delivery -> CompletableFuture.runAsync(() -> deliver(delivery), executor))
                    .toList();
            CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
        } catch (RuntimeException failure) {
            log.warn("Alarm delivery scan failed; next scan will retry: {}", failure.getMessage());
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
        String previousTenant = TenantContext.get();
        String previousTrace = MDC.get("traceId");
        try {
            Instant now = Instant.now();
            if (repository.claim(delivery.getId(), now, maxAttempts) != 1) return;
            claimed = true;
            TenantContext.set(delivery.getTenantId());
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
            if (previousTenant == null) TenantContext.clear();
            else TenantContext.set(previousTenant);
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
        int attempts = delivery.getAttempts() + 1;
        long delaySeconds = Math.min(900, 1L << Math.min(10, Math.max(1, attempts)));
        Instant now = Instant.now();
        String safeError = error == null ? "unknown delivery failure" : error;
        if (safeError.length() > MAX_ERROR_LENGTH) safeError = safeError.substring(0, MAX_ERROR_LENGTH);
        try {
            if (attempts >= maxAttempts) {
                if (repository.markDead(delivery.getId(), safeError, now) == 1) {
                    log.error("Alarm delivery moved to DEAD alarmId={} destination={} attempts={} reason={}",
                            delivery.getAlarmId(), delivery.getDestination(), attempts, safeError);
                    lifecycle("dead", 1);
                }
                return;
            }
            Instant nextAttempt = now.plusSeconds(delaySeconds);
            if (repository.scheduleRetry(delivery.getId(), nextAttempt, safeError, now) == 1) {
                log.warn("Alarm delivery retry scheduled alarmId={} destination={} attempts={} next={} reason={}",
                        delivery.getAlarmId(), delivery.getDestination(), attempts, nextAttempt, safeError);
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
    void cleanupDelivered() {
        try {
            int removed = repository.deleteDeliveredBefore(Instant.now().minusMillis(retentionMs));
            if (removed > 0) {
                log.info("Removed retained alarm delivery rows count={}", removed);
                lifecycle("cleaned", removed);
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
        executor.shutdownNow();
    }

    private record DeliveryResult(boolean delivered, String error) {
        static DeliveryResult success() { return new DeliveryResult(true, null); }
        static DeliveryResult failure(String error) { return new DeliveryResult(false, error); }
    }
}
