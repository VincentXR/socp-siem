package com.socp.rule;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AlertStableIdTest {

    @Test
    void unorderedEvidenceIdentitySurvivesReplayWithDifferentArrivalOrder() {
        SecurityEvent firstEvent = event("event-a");
        SecurityEvent secondEvent = event("event-b");

        Alert first = Alert.withUnorderedEvidence("THRESHOLD", "Threshold", Severity.HIGH,
                "threshold", "10.0.0.1", List.of(firstEvent, secondEvent));
        Alert replay = Alert.withUnorderedEvidence("THRESHOLD", "Threshold", Severity.HIGH,
                "threshold", "10.0.0.1", List.of(secondEvent, firstEvent));

        assertEquals(first.id(), replay.id());
        assertEquals(List.of("event-b", "event-a"),
                replay.evidence().stream().map(SecurityEvent::id).toList());
    }

    @Test
    void orderedEvidenceIdentityStillPreservesSequenceSemantics() {
        SecurityEvent firstEvent = event("event-a");
        SecurityEvent secondEvent = event("event-b");

        Alert first = new Alert("CORRELATION", "Correlation", Severity.HIGH,
                "correlation", "10.0.0.1", List.of(firstEvent, secondEvent));
        Alert reversed = new Alert("CORRELATION", "Correlation", Severity.HIGH,
                "correlation", "10.0.0.1", List.of(secondEvent, firstEvent));

        assertNotEquals(first.id(), reversed.id());
    }

    private static SecurityEvent event(String id) {
        return new SecurityEvent(id, Instant.parse("2026-08-28T00:00:00Z"), "firewall",
                "host-1", "RDP connection", Map.of("tenant_id", "tenant-a"), Severity.HIGH);
    }
}
