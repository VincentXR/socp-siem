package com.socp.rule;

import com.socp.rule.engine.AlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.partition.DetectionDelivery;
import com.socp.rule.partition.RoutingDimension;
import com.socp.rule.rules.PatternRule;
import com.socp.rule.rules.Rule;
import com.socp.rule.rules.ThresholdRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutedRuleEngineTest {

    @Test
    void statelessRunsOnceAndUserStateCorrelatesAcrossIpAndHostCopies() throws Exception {
        List<Alert> alerts = new CopyOnWriteArrayList<>();
        AlertSink sink = new AlertSink() {
            @Override public void publish(Alert alert) { alerts.add(alert); }
            @Override public void close() { }
        };
        Rule stateless = new PatternRule("STATELESS", "stateless", ignored -> true,
                Severity.LOW, "stateless");
        Rule userState = new ThresholdRule("USER-TWO", "user-two", ignored -> true,
                event -> RoutingDimension.value(event, "user"), 2,
                Duration.ofMinutes(5), Severity.HIGH, "user threshold");

        try (RuleEngine engine = new RuleEngine(
                List.of(stateless, userState), List.of(sink), null, null, null, null,
                Map.of(), Map.of("USER-TWO", new RuleEngine.RoutingDimension("user", 300)))) {
            engine.start();
            SecurityEvent first = source("event-1", "tenant-a", "alice",
                    "host-a", "198.51.100.10", Instant.parse("2026-01-01T00:00:00Z"));
            SecurityEvent second = source("event-2", "tenant-a", "alice",
                    "host-b", "203.0.113.20", Instant.parse("2026-01-01T00:00:10Z"));

            engine.ingestAndAwait(delivery(first, DetectionDelivery.Kind.STATELESS,
                    DetectionDelivery.STATELESS_DIMENSION, "_once"))
                    .get(3, TimeUnit.SECONDS);
            engine.ingestAndAwait(delivery(first, DetectionDelivery.Kind.STATEFUL,
                    "user", "alice")).get(3, TimeUnit.SECONDS);
            engine.ingestAndAwait(delivery(second, DetectionDelivery.Kind.STATEFUL,
                    "user", "alice")).get(3, TimeUnit.SECONDS);

            assertEquals(1, alerts.stream().filter(a -> a.ruleId().equals("STATELESS")).count(),
                    "stateless rule must execute only on the singleton delivery");
            List<Alert> userAlerts = alerts.stream()
                    .filter(a -> a.ruleId().equals("USER-TWO")).toList();
            assertEquals(1, userAlerts.size());
            assertEquals(List.of("event-1", "event-2"),
                    userAlerts.getFirst().evidence().stream().map(SecurityEvent::id).toList());
            assertEquals("alice", userAlerts.getFirst().entity());
        }
    }

    @Test
    void tenantOwnedEnginesKeepSameNamedUsersIsolated() throws Exception {
        List<Alert> tenantA = new CopyOnWriteArrayList<>();
        List<Alert> tenantB = new CopyOnWriteArrayList<>();
        try (RuleEngine a = userThresholdEngine(tenantA);
             RuleEngine b = userThresholdEngine(tenantB)) {
            a.start();
            b.start();
            a.ingestAndAwait(delivery(source("a-1", "tenant-a", "same-user",
                    "a-host", "10.0.0.1", Instant.EPOCH),
                    DetectionDelivery.Kind.STATEFUL, "user", "same-user"))
                    .get(3, TimeUnit.SECONDS);
            b.ingestAndAwait(delivery(source("b-1", "tenant-b", "same-user",
                    "b-host", "10.0.0.2", Instant.EPOCH.plusSeconds(1)),
                    DetectionDelivery.Kind.STATEFUL, "user", "same-user"))
                    .get(3, TimeUnit.SECONDS);

            assertTrue(tenantA.isEmpty());
            assertTrue(tenantB.isEmpty());

            a.ingestAndAwait(delivery(source("a-2", "tenant-a", "same-user",
                    "a-host-2", "10.0.0.3", Instant.EPOCH.plusSeconds(2)),
                    DetectionDelivery.Kind.STATEFUL, "user", "same-user"))
                    .get(3, TimeUnit.SECONDS);
            assertEquals(1, tenantA.size());
            assertTrue(tenantB.isEmpty());
        }
    }

    @Test
    void routeVersionChangesDeliveryIdentityButNotBusinessAlertIdentity() {
        SecurityEvent source = source("source-7", "tenant-a", "alice",
                "host-a", "10.0.0.7", Instant.EPOCH);
        SecurityEvent v2 = delivery(source, DetectionDelivery.Kind.STATEFUL, "user", "alice");
        Map<String, String> v3Fields = new LinkedHashMap<>(v2.fields());
        String v3DeliveryId = DetectionDelivery.deliveryId("tenant-a", source.id(),
                "detection-routing-v3", DetectionDelivery.Kind.STATEFUL, "user", "alice");
        v3Fields.put(DetectionDelivery.DELIVERY_ID_FIELD, v3DeliveryId);
        v3Fields.put(DetectionDelivery.ROUTING_VERSION_FIELD, "detection-routing-v3");
        SecurityEvent v3 = new SecurityEvent(source.id(), source.timestamp(), source.source(),
                source.host(), source.raw(), Map.copyOf(v3Fields), source.severity());

        assertNotEquals(DetectionDelivery.deliveryId(v2), DetectionDelivery.deliveryId(v3));
        Alert first = new Alert("RULE", "Rule", Severity.HIGH,
                "message", "alice", List.of(v2));
        Alert replayed = new Alert("RULE", "Rule", Severity.HIGH,
                "message", "alice", List.of(v3));
        assertEquals(first.id(), replayed.id(),
                "business alert id must be based on source evidence, not delivery/version identity");
    }

    private static RuleEngine userThresholdEngine(List<Alert> alerts) {
        Rule rule = new ThresholdRule("USER-TWO", "user-two", ignored -> true,
                event -> RoutingDimension.value(event, "user"), 2,
                Duration.ofMinutes(5), Severity.HIGH, "user threshold");
        AlertSink sink = new AlertSink() {
            @Override public void publish(Alert alert) { alerts.add(alert); }
            @Override public void close() { }
        };
        return new RuleEngine(List.of(rule), List.of(sink), null, null, null, null,
                Map.of(), Map.of("USER-TWO", new RuleEngine.RoutingDimension("user", 300)));
    }

    private static SecurityEvent source(String id, String tenant, String user, String host,
                                        String srcIp, Instant timestamp) {
        return new SecurityEvent(id, timestamp, "auth", host, "raw",
                Map.of("tenant_id", tenant, "user", user, "src_ip", srcIp),
                Severity.INFO);
    }

    private static SecurityEvent delivery(SecurityEvent source, DetectionDelivery.Kind kind,
                                          String dimension, String value) {
        Map<String, String> fields = new LinkedHashMap<>(source.fields());
        String deliveryId = DetectionDelivery.deliveryId(source.requireTenantId(), source.id(),
                DetectionDelivery.ROUTING_VERSION, kind, dimension, value);
        fields.put(DetectionDelivery.DELIVERY_ID_FIELD, deliveryId);
        fields.put(DetectionDelivery.SOURCE_EVENT_ID_FIELD, source.id());
        fields.put(DetectionDelivery.KIND_FIELD, kind.name());
        fields.put(DetectionDelivery.DIMENSION_FIELD, dimension);
        fields.put(DetectionDelivery.VALUE_FIELD, value);
        fields.put(DetectionDelivery.ROUTING_VERSION_FIELD, DetectionDelivery.ROUTING_VERSION);
        return new SecurityEvent(source.id(), source.timestamp(), source.source(), source.host(),
                source.raw(), Map.copyOf(fields), source.severity());
    }
}
