package com.socp.detect.web.metrics;

import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.platform.client.http.ServiceCall;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DetectionPerformanceMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeRegistry() {
        registry.close();
    }

    @Test
    void recordsEventLifecycleAndRemovesCompletedTiming() {
        DetectionPerformanceMetrics metrics = new DetectionPerformanceMetrics(registry);
        Instant ingest = Instant.now().minusSeconds(2);
        SecurityEvent event = event("event-metrics", Map.of(
                "tenant_id", "tenant-metrics",
                "socp_bench_ingest_time", ingest.toString()));

        metrics.kafkaReceived(event);
        metrics.journalCommitted(event);
        metrics.evaluationCompleted(event, 1);
        metrics.durableSinksCompleted(event, 1);

        assertEquals(1, registry.find("socp.detection.event.completed")
                .tag("outcome", "alert").counter().count(), 0.001);
        assertEquals(1, registry.find("socp.detection.db.transactions")
                .tag("operation", "journal_claim").counter().count(), 0.001);
        assertEquals(1, registry.find("socp.detection.db.transactions")
                .tag("operation", "outbox_and_completion").counter().count(), 0.001);
        assertEquals(1, registry.find("socp.detection.event.stage")
                .tag("stage", "kafka_queue").timer().count());
        assertEquals(1, registry.find("socp.detection.event.stage")
                .tag("stage", "consumer_to_durable").timer().count());

        // The completion callback is idempotent from the metrics perspective:
        // the event timing is removed after the first durable completion.
        metrics.durableSinksCompleted(event, 0);
        assertEquals(1, registry.find("socp.detection.event.completed")
                .tag("outcome", "alert").counter().count(), 0.001);
        var noAlert = registry.find("socp.detection.event.completed")
                .tag("outcome", "no_alert").counter();
        assertEquals(0, noAlert == null ? 0 : noAlert.count(), 0.001);
    }

    @Test
    void recordsTerminalFailureAndIgnoresMissingEvents() {
        DetectionPerformanceMetrics metrics = new DetectionPerformanceMetrics(registry);
        SecurityEvent event = event("event-terminal", Map.of("tenant_id", "tenant-metrics"));

        metrics.kafkaReceived(event);
        metrics.terminalWithoutEvaluation(event, "malformed");
        metrics.terminalWithoutEvaluation((SecurityEvent) null, "ignored");
        metrics.processingFailed(null, new IllegalStateException("dependency"));
        metrics.processingFailed(event, new IllegalStateException("dependency"));

        assertEquals(1, registry.find("socp.detection.event.terminal")
                .tag("outcome", "malformed").counter().count(), 0.001);
        assertEquals(2, registry.find("socp.detection.event.completed")
                .tag("outcome", "failed").counter().count(), 0.001);
    }

    @Test
    void recordsAlertOutboxRoundTripAndLifecycle() {
        DetectionPerformanceMetrics metrics = new DetectionPerformanceMetrics(registry);
        DetectionAlertOutboxEntity outbox = new DetectionAlertOutboxEntity(
                "alert-metrics", "tenant-metrics", "{}", Instant.now().minusSeconds(1));

        assertNotNull(metrics.outboxClaimed(outbox));
        metrics.alertAcknowledged("alert-metrics", new ServiceCall(
                null, "http://alert", true, 200,
                "{\"data\":{\"createdAt\":\"" + Instant.now() + "\"}}",
                null, 1, false, 1));
        metrics.outboxStateTransaction("claim");
        metrics.outboxLifecycle("detection-alert", "published", 2);
        metrics.outboxLifecycle("detection-alert", "ignored", 0);

        assertEquals(1, registry.find("socp.detection.alert.stage")
                .tag("stage", "outbox_queue").timer().count());
        assertEquals(1, registry.find("socp.detection.alert.stage")
                .tag("stage", "http_round_trip").timer().count());
        assertEquals(1, registry.find("socp.detection.db.transactions")
                .tag("operation", "claim").counter().count(), 0.001);
        assertEquals(2, registry.find("socp.detection.outbox.lifecycle")
                .tag("outbox", "detection-alert")
                .tag("outcome", "published").counter().count(), 0.001);
    }

    @Test
    void handlesInvalidOptionalTimestampsAndDeliveryFailures() {
        DetectionPerformanceMetrics metrics = new DetectionPerformanceMetrics(registry);
        SecurityEvent event = event("event-invalid-time", Map.of(
                "tenant_id", "tenant-metrics",
                "socp_bench_ingest_time", "not-an-instant",
                "ingested_at", Instant.now().minusSeconds(1).toString()));

        metrics.kafkaReceived(null);
        metrics.kafkaReceived(event);
        metrics.outboxClaimed(null);
        metrics.alertAcknowledged("missing-alert", null);
        metrics.alertDeliveryFailed("missing-alert");
        metrics.alertDeliveryFailed(null);

        DetectionAlertOutboxEntity noCreatedAt = new DetectionAlertOutboxEntity(
                "alert-no-created-at", "tenant-metrics", "{}", Instant.now());
        metrics.outboxClaimed(noCreatedAt);
        metrics.alertAcknowledged("alert-no-created-at", new ServiceCall(
                null, "http://alert", true, 200, "not-json", null, 1, false, 1));

        assertEquals(2, registry.find("socp.detection.alert.delivery")
                .tag("outcome", "failed").counter().count(), 0.001);
        assertEquals(1, registry.find("socp.detection.alert.stage")
                .tag("stage", "outbox_queue").timer().count());
    }

    private static SecurityEvent event(String id, Map<String, String> fields) {
        return new SecurityEvent(id, Instant.now(), "auth", "host-1", "raw", fields, Severity.HIGH);
    }
}
