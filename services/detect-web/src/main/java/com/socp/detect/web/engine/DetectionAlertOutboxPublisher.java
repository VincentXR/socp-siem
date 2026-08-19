package com.socp.detect.web.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.store.DetectionAlertOutboxEntity;
import com.socp.detect.web.store.DetectionAlertOutboxRepository;
import com.socp.platform.client.AlertClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    private final DetectionAlertOutboxRepository repository;
    private final AlertClient alertClient;
    private final AlarmKafkaProducer alarmProducer;

    public DetectionAlertOutboxPublisher(DetectionAlertOutboxRepository repository,
                                         AlertClient alertClient,
                                         AlarmKafkaProducer alarmProducer) {
        this.repository = repository;
        this.alertClient = alertClient;
        this.alarmProducer = alarmProducer;
    }

    @Scheduled(fixedDelayString = "${socp.detect.alert-outbox.poll-interval-ms:1000}",
            initialDelayString = "${socp.detect.alert-outbox.initial-delay-ms:1000}")
    public void publishDue() {
        try {
            recoverStaleClaims();
            publishStage("PENDING");
            publishStage("DELIVERED");
        } catch (Exception ex) {
            log.warn("Detection alert outbox scan failed; next scan will retry: {}", ex.getMessage());
        }
    }

    private void publishStage(String stage) {
        List<DetectionAlertOutboxEntity> due = repository
                .findTop100ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(stage, Instant.now());
        for (DetectionAlertOutboxEntity event : due) {
            if (!claim(event, stage)) continue;
            if ("PENDING".equals(stage)) {
                deliverToAlertWeb(event);
            } else {
                publishOriginalAlarm(event);
            }
        }
    }

    private boolean claim(DetectionAlertOutboxEntity event, String expectedStage) {
        try {
            return repository.claim(event.getAlertId(), expectedStage, Instant.now()) == 1;
        } catch (Exception ex) {
            log.warn("Unable to claim Detection alert outbox alertId={}: {}", event.getAlertId(), ex.getMessage());
            return false;
        }
    }

    private void deliverToAlertWeb(DetectionAlertOutboxEntity event) {
        ServiceCall call;
        try {
            call = withTenant(event, () -> alertClient.forwardAlarm(event.getPayload()));
        } catch (Exception ex) {
            fail(event, "alert-web exception: " + ex.getMessage(), "PENDING");
            return;
        }
        if (call == null || !call.ok()) {
            fail(event, "alert-web: " + (call == null ? "empty service response" : call.failureReason()), "PENDING");
            return;
        }
        Instant now = Instant.now();
        event.setStatus("DELIVERED");
        event.setDeliveredAt(now);
        event.setNextAttemptAt(now);
        event.setUpdatedAt(now);
        event.setLastError(null);
        save(event);
        log.debug("Detection alert accepted by alert-web alertId={}", event.getAlertId());
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
        }
    }

    @Transactional
    void fail(DetectionAlertOutboxEntity event, String reason, String stage) {
        int attempts = event.getAttempts() + 1;
        Instant now = Instant.now();
        long delaySeconds = Math.min(60, 1L << Math.min(attempts, 6));
        event.setAttempts(attempts);
        event.setStatus(stage);
        event.setNextAttemptAt(now.plusSeconds(delaySeconds));
        event.setUpdatedAt(now);
        event.setLastError(truncate(reason));
        repository.save(event);
        log.warn("Detection alert outbox retry scheduled alertId={} stage={} attempts={} next={} reason={}",
                event.getAlertId(), stage, attempts, event.getNextAttemptAt(), event.getLastError());
    }

    @Transactional
    void save(DetectionAlertOutboxEntity event) {
        repository.save(event);
    }

    private static <T> T withTenant(DetectionAlertOutboxEntity event,
                                    java.util.function.Supplier<T> action) {
        String previous = TenantContext.get();
        try {
            TenantContext.set(event.getTenantId());
            return action.get();
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.set(previous);
        }
    }

    private static String truncate(String value) {
        if (value == null) return "unknown";
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }
}
