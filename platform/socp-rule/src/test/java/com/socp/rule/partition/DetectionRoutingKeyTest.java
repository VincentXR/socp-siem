package com.socp.rule.partition;

import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Alert;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DetectionRoutingKeyTest {

    @Test
    void sameTenantAndEntityUseTheSameKey() {
        SecurityEvent first = event("evt-1", Map.of("tenant_id", "acme", "src_ip", "198.51.100.10"));
        SecurityEvent retry = event("evt-2", Map.of("tenant_id", "acme", "src_ip", "198.51.100.10"));

        assertEquals(DetectionRoutingKey.forEvent(first), DetectionRoutingKey.forEvent(retry));
        assertTrue(DetectionRoutingKey.isPartitionLocal(first, "src_ip"));
    }

    @Test
    void entityFieldIsPartOfTheKey() {
        SecurityEvent host = event("evt-1", Map.of("tenant_id", "acme", "host", "same"));
        SecurityEvent user = event("evt-2", Map.of("tenant_id", "acme", "user", "same"));

        assertNotEquals(DetectionRoutingKey.forEvent(host), DetectionRoutingKey.forEvent(user));
        assertFalse(DetectionRoutingKey.isPartitionLocal(host, "src_ip"));
    }

    @Test
    void explicitRoutingContractWinsOverFallback() {
        SecurityEvent event = event("evt-1", Map.of(
                "tenant_id", "acme",
                "src_ip", "198.51.100.10",
                "detection_routing_field", "user",
                "detection_routing_value", "alice"));

        assertEquals("acme|user|alice", DetectionRoutingKey.forEvent(event));
        assertTrue(DetectionRoutingKey.isPartitionLocal(event, "user"));
    }

    @Test
    void separatorsInUntrustedValuesCannotAliasAnotherEntity() {
        SecurityEvent first = event("evt-1", Map.of(
                "tenant_id", "acme|prod", "src_ip", "198.51.100.10"));
        SecurityEvent second = event("evt-2", Map.of(
                "tenant_id", "acme", "src_ip", "prod|198.51.100.10"));

        assertNotEquals(DetectionRoutingKey.forEvent(first), DetectionRoutingKey.forEvent(second));
    }

    @Test
    void replayingTheSameEvidenceProducesTheSameAlertId() {
        SecurityEvent evidence = event("evt-1", Map.of("src_ip", "198.51.100.10"));
        Alert first = new Alert("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed", "198.51.100.10", java.util.List.of(evidence));
        Alert replay = new Alert("AUTH-BRUTE", "SSH brute force", Severity.HIGH,
                "failed", "198.51.100.10", java.util.List.of(evidence));

        assertEquals(first.id(), replay.id());
        assertEquals(evidence.timestamp(), first.timestamp());
    }

    private static SecurityEvent event(String id, Map<String, String> fields) {
        return new SecurityEvent(id, Instant.parse("2026-08-19T00:00:00Z"), "auth", "host-1",
                "event", fields, Severity.INFO);
    }
}
