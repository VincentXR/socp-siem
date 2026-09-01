package com.socp.detect.web.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.platform.client.http.ServiceCall;
import com.socp.rule.engine.RuleProcessingObserver;
import com.socp.rule.model.SecurityEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance-closure measurements for the event path (all events) and the
 * alert path (only events that emit alerts). Meter tags are deliberately
 * bounded so a long benchmark cannot create one time series per event.
 */
@Component
public class DetectionPerformanceMetrics implements RuleProcessingObserver {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> INGEST_FIELDS = List.of(
            "socp_bench_ingest_time", "ingested_at", "ingest_time", "socp.ingest_time");

    private final MeterRegistry registry;
    private final Map<String, EventTiming> events = new ConcurrentHashMap<>();
    private final Map<String, AlertTiming> alerts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> backlogGauges = new ConcurrentHashMap<>();

    public DetectionPerformanceMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** T1: Kafka record has entered the Detection consumer. */
    public void kafkaReceived(SecurityEvent event) {
        if (event == null || event.id() == null) return;
        long now = System.nanoTime();
        events.put(event.scopedId(), new EventTiming(now));
        Instant ingestedAt = ingestTime(event);
        if (ingestedAt != null) {
            recordInstant("socp.detection.event.stage", "kafka_queue", ingestedAt, Instant.now());
        }
    }

    /** T2: the Journal PENDING transaction returned successfully. */
    public void journalCommitted(String eventId) {
        EventTiming timing = events.get(eventId);
        if (timing == null) return;
        long now = System.nanoTime();
        recordNanos("socp.detection.event.stage", "journal", now - timing.receivedNanos);
        timing.journalNanos = now;
        registry.counter("socp.detection.db.transactions", "scope", "event",
                "operation", "journal_claim").increment();
    }

    public void journalCommitted(SecurityEvent event) {
        if (event != null) journalCommitted(event.scopedId());
    }

    public void terminalWithoutEvaluation(String eventId, String outcome) {
        events.remove(eventId);
        registry.counter("socp.detection.event.terminal", "outcome", outcome).increment();
    }

    public void terminalWithoutEvaluation(SecurityEvent event, String outcome) {
        if (event != null) terminalWithoutEvaluation(event.scopedId(), outcome);
    }

    @Override
    public void evaluationCompleted(SecurityEvent event, int emittedAlerts) {
        EventTiming timing = timing(event);
        if (timing == null) return;
        long now = System.nanoTime();
        long start = timing.journalNanos == 0 ? timing.receivedNanos : timing.journalNanos;
        recordNanos("socp.detection.event.stage", "rule_evaluation", now - start);
        timing.evaluationNanos = now;
    }

    @Override
    public void durableSinksCompleted(SecurityEvent event, int emittedAlerts) {
        EventTiming timing = timing(event);
        if (timing == null) return;
        long now = System.nanoTime();
        long start = timing.evaluationNanos == 0
                ? (timing.journalNanos == 0 ? timing.receivedNanos : timing.journalNanos)
                : timing.evaluationNanos;
        recordNanos("socp.detection.event.stage", "durable_completion", now - start);
        recordNanos("socp.detection.event.stage", "consumer_to_durable", now - timing.receivedNanos);
        registry.counter("socp.detection.event.completed", "outcome",
                emittedAlerts > 0 ? "alert" : "no_alert").increment();
        registry.counter("socp.detection.db.transactions", "scope", "event",
                "operation", "outbox_and_completion").increment();
        events.remove(event.scopedId());
    }

    @Override
    public void processingFailed(SecurityEvent event, Throwable failure) {
        if (event != null) events.remove(event.scopedId());
        registry.counter("socp.detection.event.completed", "outcome", "failed").increment();
    }

    /** T5: a persisted alert outbox row has been claimed for HTTP delivery. */
    public Instant outboxClaimed(DetectionAlertOutboxEntity event) {
        Instant now = Instant.now();
        if (event == null || event.getAlertId() == null) return now;
        alerts.put(event.getAlertId(), new AlertTiming(System.nanoTime()));
        recordInstant("socp.detection.alert.stage", "outbox_queue", event.getCreatedAt(), now);
        registry.counter("socp.detection.db.transactions", "scope", "alert",
                "operation", "outbox_claim").increment();
        return now;
    }

    /** T8: Detection received Alert Web's HTTP acknowledgement. */
    public void alertAcknowledged(String alertId, ServiceCall call) {
        if (alertId == null) return;
        AlertTiming timing = alerts.remove(alertId);
        if (timing == null) return;
        long nowNanos = System.nanoTime();
        recordNanos("socp.detection.alert.stage", "http_round_trip", nowNanos - timing.claimedNanos);
        Instant committedAt = responseCreatedAt(call);
        if (committedAt != null) {
            recordInstant("socp.detection.alert.stage", "response", committedAt, Instant.now());
        }
    }

    public void alertDeliveryFailed(String alertId) {
        if (alertId != null) alerts.remove(alertId);
        registry.counter("socp.detection.alert.delivery", "outcome", "failed").increment();
    }

    public void outboxStateTransaction(String operation) {
        registry.counter("socp.detection.db.transactions", "scope", "alert",
                "operation", operation).increment();
    }

    /** Records bounded lifecycle outcomes for durable publisher rows. */
    public void outboxLifecycle(String outbox, String outcome, int count) {
        if (count <= 0) return;
        registry.counter("socp.detection.outbox.lifecycle", "outbox", outbox,
                "outcome", outcome).increment(count);
    }

    public void outboxDrain(String outbox, int rounds, long durationNanos) {
        registry.summary("socp.detection.outbox.drain.rounds", "outbox", outbox)
                .record(Math.max(0, rounds));
        Timer.builder("socp.detection.outbox.drain.duration")
                .tag("outbox", outbox)
                .maximumExpectedValue(Duration.ofMinutes(1))
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void outboxBacklog(String outbox, long pendingCount, Instant oldestPending,
                              long deadCount, Instant oldestDead) {
        Instant now = Instant.now();
        gauge("socp.detection.outbox.pending.count", outbox).set(Math.max(0L, pendingCount));
        gauge("socp.detection.outbox.oldest.pending.age.seconds", outbox)
                .set(ageSeconds(oldestPending, now));
        gauge("socp.detection.outbox.dead.count", outbox).set(Math.max(0L, deadCount));
        gauge("socp.detection.outbox.oldest.dead.age.seconds", outbox)
                .set(ageSeconds(oldestDead, now));
    }

    private AtomicLong gauge(String name, String outbox) {
        String key = name + ':' + outbox;
        return backlogGauges.computeIfAbsent(key, ignored -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder(name, value, AtomicLong::get)
                    .tag("outbox", outbox)
                    .register(registry);
            return value;
        });
    }

    private static long ageSeconds(Instant value, Instant now) {
        return value == null ? 0L : Math.max(0L, Duration.between(value, now).toSeconds());
    }

    @Scheduled(fixedDelayString = "${socp.detect.metrics.timing-cleanup-interval-ms:60000}")
    void cleanupAbandonedTimings() {
        long cutoff = System.nanoTime() - Duration.ofMinutes(10).toNanos();
        events.entrySet().removeIf(entry -> entry.getValue().receivedNanos < cutoff);
        alerts.entrySet().removeIf(entry -> entry.getValue().claimedNanos < cutoff);
    }

    private EventTiming timing(SecurityEvent event) {
        return event == null || event.id() == null ? null : events.get(event.scopedId());
    }

    private void recordNanos(String name, String stage, long nanos) {
        timer(name, stage).record(Math.max(0L, nanos), TimeUnit.NANOSECONDS);
    }

    private void recordInstant(String name, String stage, Instant start, Instant end) {
        if (start == null || end == null) return;
        timer(name, stage).record(Math.max(0L, Duration.between(start, end).toNanos()),
                TimeUnit.NANOSECONDS);
    }

    private Timer timer(String name, String stage) {
        return Timer.builder(name)
                .tag("stage", stage)
                .maximumExpectedValue(Duration.ofMinutes(10))
                .publishPercentileHistogram()
                .register(registry);
    }

    private static Instant ingestTime(SecurityEvent event) {
        if (event.fields() == null) return null;
        for (String key : INGEST_FIELDS) {
            String value = event.fields().get(key);
            if (value == null || value.isBlank()) continue;
            try {
                return Instant.parse(value);
            } catch (RuntimeException ignored) {
                // Optional collector timestamp; the event remains valid.
            }
        }
        return null;
    }

    private static Instant responseCreatedAt(ServiceCall call) {
        if (call == null || call.body() == null || call.body().isBlank()) return null;
        try {
            JsonNode root = JSON.readTree(call.body());
            JsonNode data = root.path("data");
            String value = data.path("createdAt").asText(null);
            return value == null ? null : Instant.parse(value);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return null;
        }
    }

    private static final class EventTiming {
        private final long receivedNanos;
        private volatile long journalNanos;
        private volatile long evaluationNanos;
        private EventTiming(long receivedNanos) {
            this.receivedNanos = receivedNanos;
        }
    }

    private record AlertTiming(long claimedNanos) {
    }
}
