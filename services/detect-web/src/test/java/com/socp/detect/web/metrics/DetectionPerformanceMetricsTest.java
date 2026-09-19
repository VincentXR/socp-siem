package com.socp.detect.web.metrics;

import com.socp.detect.web.persistence.entity.DetectionAlertOutboxEntity;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void countsRoutingMismatchPerRuleAndFieldPair() {
        DetectionPerformanceMetrics metrics = new DetectionPerformanceMetrics(registry);
        SecurityEvent event = event("event-routing", Map.of("tenant_id", "tenant-metrics"));

        metrics.routingMismatched(event, "AUTH-BRUTE", "user", "src_ip");
        metrics.routingMismatched(event, "AUTH-BRUTE", "user", "src_ip");
        metrics.routingMismatched(event, "LATERAL", "host", null);
        metrics.routingMismatched(event, "   ", "", "  ");
        metrics.routingMismatched(null, "x".repeat(200), "declared", "event");

        assertEquals(2, registry.find(DetectionPerformanceMetrics.ROUTING_MISMATCH)
                .tag("rule", "AUTH-BRUTE").tag("declared_field", "user")
                .tag("event_field", "src_ip").counter().count(), 0.001);
        assertEquals(1, registry.find(DetectionPerformanceMetrics.ROUTING_MISMATCH)
                .tag("rule", "LATERAL").tag("declared_field", "host")
                .tag("event_field", "unknown").counter().count(), 0.001);
        assertEquals(1, registry.find(DetectionPerformanceMetrics.ROUTING_MISMATCH)
                .tag("rule", "unknown").tag("declared_field", "unknown")
                .tag("event_field", "unknown").counter().count(), 0.001);
        // An oversized rule id is truncated rather than becoming a label of
        // unbounded size.
        assertEquals(1, registry.find(DetectionPerformanceMetrics.ROUTING_MISMATCH)
                .tag("rule", "x".repeat(128)).counter().count(), 0.001);
    }

    @Test
    void exportsEngineStatsAsGauges() {
        DetectionPerformanceMetrics metrics = new DetectionPerformanceMetrics(registry);

        metrics.applyEngineStats("tenant-metrics", Map.of(
                "isolatedRules", 3,
                "routingMismatchWindows", 7L,
                "stateRecovery", Map.of("warmedWithoutHistory", 2, "rebuildRetries", 9)));

        assertEquals(3, gaugeValue(DetectionPerformanceMetrics.ISOLATED_RULES,
                "tenant", "tenant-metrics"), 0.001);
        assertEquals(7, gaugeValue(DetectionPerformanceMetrics.ROUTING_MISMATCH_WINDOWS,
                "tenant", "tenant-metrics"), 0.001);
        assertEquals(2, gaugeValue(DetectionPerformanceMetrics.WARMED_WITHOUT_HISTORY,
                "scope", "replica-local"), 0.001);
        assertEquals(9, gaugeValue(DetectionPerformanceMetrics.REBUILD_RETRIES,
                "scope", "replica-local"), 0.001);

        // A later snapshot replaces the value instead of summing into it, and a
        // missing or malformed field reads as zero rather than throwing.
        metrics.applyEngineStats("tenant-metrics", Map.of("isolatedRules", 0));
        assertEquals(0, gaugeValue(DetectionPerformanceMetrics.ISOLATED_RULES,
                "tenant", "tenant-metrics"), 0.001);
        metrics.applyEngineStats("tenant-broken", Map.of("isolatedRules", "not-a-number"));
        assertEquals(0, gaugeValue(DetectionPerformanceMetrics.ISOLATED_RULES,
                "tenant", "tenant-broken"), 0.001);
        metrics.applyEngineStats("tenant-nomap", Map.of("routingMismatchWindows", 1,
                "stateRecovery", "closed"));
        assertEquals(1, gaugeValue(DetectionPerformanceMetrics.ROUTING_MISMATCH_WINDOWS,
                "tenant", "tenant-nomap"), 0.001);
        assertEquals(9, gaugeValue(DetectionPerformanceMetrics.REBUILD_RETRIES,
                "scope", "replica-local"), 0.001);
        metrics.applyEngineStats("tenant-null", null);
    }

    @Test
    void scrapesEngineStatsOnlyForTenantsSeenOnTheEventPath() {
        AtomicInteger reads = new AtomicInteger();
        java.util.List<String> scopedTenants = new java.util.concurrent.CopyOnWriteArrayList<>();
        DetectEngineService engine = mock(DetectEngineService.class);
        when(engine.stats()).thenAnswer(invocation -> {
            scopedTenants.add(TenantContext.get());
            reads.incrementAndGet();
            return Map.of("isolatedRules", 5);
        });
        DetectionPerformanceMetrics metrics =
                new DetectionPerformanceMetrics(registry, providerOf(engine));

        metrics.kafkaReceived(event("event-scrape", Map.of("tenant_id", "tenant-scrape")));
        metrics.routingMismatched(event("event-scrape-2", Map.of("tenant_id", "tenant-other")),
                "R-1", "user", "host");
        // A tenant id that could never be a label must not reach the scope API.
        metrics.kafkaReceived(event("event-invalid", Map.of("tenant_id", "not a tenant!")));
        metrics.kafkaReceived(null);

        metrics.refreshEngineStatGauges();

        assertEquals(2, reads.get());
        assertEquals(java.util.Set.of("tenant-scrape", "tenant-other"),
                new java.util.HashSet<>(scopedTenants));
        assertEquals(5, gaugeValue(DetectionPerformanceMetrics.ISOLATED_RULES,
                "tenant", "tenant-scrape"), 0.001);
        assertEquals(5, gaugeValue(DetectionPerformanceMetrics.ISOLATED_RULES,
                "tenant", "tenant-other"), 0.001);
        assertNull(registry.find(DetectionPerformanceMetrics.ISOLATED_RULES)
                .tag("tenant", "not a tenant!").gauge());
    }

    @Test
    void keepsTheLastGaugeValueWhenAnEngineStatsReadFails() {
        AtomicInteger reads = new AtomicInteger();
        DetectEngineService engine = mock(DetectEngineService.class);
        when(engine.stats()).thenAnswer(invocation -> {
            if (reads.incrementAndGet() > 1) {
                throw new IllegalStateException("detection database is unavailable");
            }
            return Map.of("isolatedRules", 4);
        });
        DetectionPerformanceMetrics metrics =
                new DetectionPerformanceMetrics(registry, providerOf(engine));
        metrics.kafkaReceived(event("event-retry", Map.of("tenant_id", "tenant-retry")));

        metrics.refreshEngineStatGauges();
        metrics.refreshEngineStatGauges();

        assertEquals(2, reads.get());
        assertEquals(4, gaugeValue(DetectionPerformanceMetrics.ISOLATED_RULES,
                "tenant", "tenant-retry"), 0.001);
        verify(engine, times(2)).stats();
    }

    @Test
    void skipsTheEngineScrapeWhenNoEngineIsAvailable() {
        DetectionPerformanceMetrics withoutProvider = new DetectionPerformanceMetrics(registry);
        withoutProvider.kafkaReceived(event("event-plain", Map.of("tenant_id", "tenant-plain")));
        withoutProvider.refreshEngineStatGauges();

        DetectionPerformanceMetrics metrics =
                new DetectionPerformanceMetrics(registry, providerOf(null));
        metrics.kafkaReceived(event("event-none", Map.of("tenant_id", "tenant-none")));
        metrics.refreshEngineStatGauges();

        // Both constructors scrape nothing, so no tenant-tagged series is created
        // even though both instances observed a tenant on the event path.
        assertNull(registry.find(DetectionPerformanceMetrics.ISOLATED_RULES).gauge());
    }

    @Test
    void boundsTenantTaggedScrapeSeriesToOneReplicaWorthOfTenants() {
        AtomicInteger reads = new AtomicInteger();
        DetectEngineService engine = mock(DetectEngineService.class);
        when(engine.stats()).thenAnswer(invocation -> {
            reads.incrementAndGet();
            return Map.of("isolatedRules", 1);
        });
        DetectionPerformanceMetrics metrics =
                new DetectionPerformanceMetrics(registry, providerOf(engine));
        // The bound exists so a hostile or accidental tenant-id flood cannot
        // turn a per-replica gauge into an unbounded series count.
        for (int index = 0; index <= DetectionPerformanceMetrics.MAX_TRACKED_TENANTS; index++) {
            metrics.kafkaReceived(event("event-" + index,
                    Map.of("tenant_id", "tenant-" + index)));
        }

        metrics.refreshEngineStatGauges();

        assertEquals(DetectionPerformanceMetrics.MAX_TRACKED_TENANTS, reads.get());
    }

    private static ObjectProvider<DetectEngineService> providerOf(DetectEngineService engine) {
        return new ObjectProvider<>() {
            @Override public DetectEngineService getObject() { return engine; }
            @Override public DetectEngineService getObject(Object... args) { return engine; }
            @Override public DetectEngineService getIfAvailable() { return engine; }
            @Override public DetectEngineService getIfUnique() { return engine; }
        };
    }

    private double gaugeValue(String name, String tagName, String tagValue) {
        io.micrometer.core.instrument.Gauge gauge =
                registry.find(name).tag(tagName, tagValue).gauge();
        assertNotNull(gauge, name + "{" + tagName + "=" + tagValue + "}");
        return gauge.value();
    }

    private static SecurityEvent event(String id, Map<String, String> fields) {
        return new SecurityEvent(id, Instant.now(), "auth", "host-1", "raw", fields, Severity.HIGH);
    }
}
