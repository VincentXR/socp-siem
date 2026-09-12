package com.socp.rule;

import com.socp.rule.engine.DetectionResult;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionResultTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void normalizesDefaultsAndBuildsAnAuditSafeSummary() {
        SecurityEvent event = event("event-1");
        Map<String, String> ruleVersions = new LinkedHashMap<>();
        ruleVersions.put("rule-a", null);
        ruleVersions.put("", "ignored");

        DetectionResult result = new DetectionResult(event, null, ruleVersions,
                null, null, null, null, " ");

        assertEquals(DetectionResult.InputPosition.unknown(), result.inputPosition());
        assertEquals(Map.of("rule-a", ""), result.ruleVersions());
        assertEquals(List.of(), result.stateChanges());
        assertEquals(List.of(), result.candidates());
        assertEquals(List.of(), result.alerts());
        assertFalse(result.hasAlerts());
        assertEquals(0, result.suppressedCount());
        assertEquals(event.scopedId(), result.idempotencyKey());
        assertEquals(EVENT_TIME, result.eventTimestamp());

        Map<String, Object> summary = result.auditSummary();
        assertEquals("event-1", summary.get("inputEventId"));
        Map<?, ?> position = (Map<?, ?>) summary.get("inputPosition");
        assertEquals(null, position.get("topic"));
        assertEquals(-1, position.get("partition"));
        assertEquals(-1L, position.get("offset"));
        assertEquals(List.of(), summary.get("candidateAlertIds"));
        assertEquals(List.of(), summary.get("emittedAlertIds"));
        assertEquals(event.scopedId(), summary.get("idempotencyKey"));
    }

    @Test
    void retainsLineageAndComputesWindowSuppression() {
        SecurityEvent event = event("event-2");
        Alert candidate = alert("candidate", event);
        Alert emitted = alert("emitted", event);
        List<Alert> windowCandidates = new ArrayList<>();
        windowCandidates.add(candidate);
        windowCandidates.add(null);
        List<Alert> windowEmitted = new ArrayList<>();
        windowEmitted.add(emitted);
        windowEmitted.add(null);
        DetectionResult.StateChange stateChange =
                new DetectionResult.StateChange("rule-a", " ", null, true);
        DetectionResult.SuppressionDecision suppression =
                DetectionResult.SuppressionDecision.window(windowCandidates, windowEmitted);

        DetectionResult result = new DetectionResult(event,
                new DetectionResult.InputPosition(" events ", 2, 42L),
                Map.of("rule-a", "v3"), List.of(stateChange),
                List.of(candidate, emitted), List.of(emitted), suppression,
                "result-key");

        assertTrue(result.hasAlerts());
        assertEquals(1, result.suppressedCount());
        assertEquals("events", result.inputPosition().topic());
        assertTrue(result.inputPosition().known());
        assertEquals("none", result.stateChanges().get(0).beforeDigest());
        assertEquals("none", result.stateChanges().get(0).afterDigest());

        Map<String, Object> summary = result.auditSummary();
        assertEquals(List.of("candidate", "emitted"), summary.get("candidateAlertIds"));
        assertEquals(List.of("emitted"), summary.get("emittedAlertIds"));
        assertEquals(List.of(Map.of("ruleId", "rule-a", "beforeDigest", "none",
                        "afterDigest", "none", "changed", true)),
                summary.get("stateChanges"));
        assertEquals("WINDOW", ((Map<?, ?>) summary.get("suppression")).get("policy"));
    }

    @Test
    void validatesPositionsSuppressionAndDigestHelpers() {
        assertEquals(DetectionResult.InputPosition.unknown(),
                new DetectionResult.InputPosition(" ", -1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult.InputPosition("events", -2, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult.InputPosition("events", 1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult.InputPosition("events", -1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult.StateChange(" ", null, null, false));

        DetectionResult.SuppressionDecision defaults =
                new DetectionResult.SuppressionDecision(null, 0, 0,
                        Arrays.asList(null, "", "alert-a", "alert-a"));
        assertEquals("NONE", defaults.policy());
        assertEquals(List.of("alert-a"), defaults.suppressedAlertIds());
        assertEquals(0, DetectionResult.SuppressionDecision.none(null, null).candidateCount());
        assertThrows(IllegalArgumentException.class,
                () -> new DetectionResult.SuppressionDecision("x", 1, 2, null));

        assertEquals("none", DetectionResult.digest(null));
        assertEquals("none", DetectionResult.digest(new byte[0]));
        assertEquals(64, DetectionResult.digest("state".getBytes()).length());
    }

    private static SecurityEvent event(String id) {
        return new SecurityEvent(id, EVENT_TIME, "auth", "host-1", "raw",
                Map.of("tenant_id", "tenant-a"), Severity.HIGH);
    }

    private static Alert alert(String id, SecurityEvent event) {
        return new Alert(id, EVENT_TIME, "rule-a", "Rule A", Severity.HIGH,
                "message", "host-1", List.of(event), "Rule A");
    }
}
