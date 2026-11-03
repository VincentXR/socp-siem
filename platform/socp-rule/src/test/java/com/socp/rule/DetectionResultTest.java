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
    void streamingAuditMatchesTheExistingMapContract() throws Exception {
        var mapper = com.socp.rule.util.Json.mapper();
        SecurityEvent event = event("escaped-\"\\\n-事件");
        Alert candidate = alert("suppressed-\"", event);
        Alert emitted = alert("emitted-\\", event);
        for (var position : List.of(DetectionResult.InputPosition.unknown(),
                new DetectionResult.InputPosition("events-\"", 2, Long.MAX_VALUE))) {
            for (String scope : List.of("full-state-v1", "affected-keys-v1", "affected-keys-merkle-v1")) {
                var result = new DetectionResult(event, position, Map.of("rule-\"", "v1\n"),
                        List.of(new DetectionResult.StateChange("rule-\"", null, "digest-\\", true, scope),
                                new DetectionResult.StateChange("unchanged", "a", "a", false, scope)),
                        List.of(candidate, emitted), List.of(emitted),
                        DetectionResult.SuppressionDecision.window(List.of(candidate, emitted), List.of(emitted)),
                        "key-\"\\\n");
                assertEquals(mapper.readTree(mapper.writeValueAsString(result.auditSummary())),
                        mapper.readTree(result.auditJson()));
            }
        }
        var empty = new DetectionResult(event, null, null, null, null, null, null, null);
        assertEquals(mapper.readTree(mapper.writeValueAsString(empty.auditSummary())), mapper.readTree(empty.auditJson()));
    }

    @Test
    void batchAuditPreservesEveryFieldAcrossVersionChangesAndCacheOverflow() throws Exception {
        var results = new ArrayList<DetectionResult>();
        var unchanged = new DetectionResult.StateChange("shared-\"\\", "a", "a", false);
        for (int i = 0; i < 256; i++) {
            var event = event("batch-" + i);
            var changes = List.of(unchanged,
                    new DetectionResult.StateChange("changed", "before-" + i, "after-" + i, true),
                    new DetectionResult.StateChange("unique-" + i, "same", "same", false, "affected-keys-merkle-v1"));
            results.add(new DetectionResult(event, new DetectionResult.InputPosition("events-\"", 0, i),
                    Map.of("rule-\"", "version-" + i / 32 + "\n"), changes,
                    List.of(alert("candidate-" + i, event)), List.of(), null, null));
        }
        var encoded = DetectionResult.auditJsonBatch(results);
        for (int i = 0; i < results.size(); i++) assertEquals(results.get(i).auditJson(), encoded.get(i));
        var large = new DetectionResult(event("large"), null,
                Map.of("large", "\"".repeat(65536)),
                List.of(new DetectionResult.StateChange("large-" + "\\".repeat(65536), "a", "a", false)),
                null, null, null, null);
        assertEquals(List.of(large.auditJson(), large.auditJson()), DetectionResult.auditJsonBatch(List.of(large, large)));
        var mixedSizes = List.of(results.getFirst(), large, results.get(255), results.get(1));
        var mixedEncoded = DetectionResult.auditJsonBatch(mixedSizes);
        for (int i = 0; i < mixedSizes.size(); i++) assertEquals(mixedSizes.get(i).auditJson(), mixedEncoded.get(i));
        assertEquals(List.of(), DetectionResult.auditJsonBatch(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DetectionResult.auditJsonBatch(java.util.Collections.nCopies(257, large)));
    }

    @Test
    void tenantValidationRetainsItsExactBoundary() {
        for (String tenant : List.of("a", "A_1-2", "a".repeat(64))) {
            var event = new SecurityEvent(EVENT_TIME, "auth", "host", "raw", Map.of("tenant_id", tenant), Severity.INFO);
            assertEquals(tenant, event.requireTenantId());
        }
        for (String tenant : List.of("", "-a", "_a", "a".repeat(65), "a\n", "a.b", "租户")) {
            var event = new SecurityEvent(EVENT_TIME, "auth", "host", "raw", Map.of("tenant_id", tenant), Severity.INFO);
            assertThrows(IllegalStateException.class, event::requireTenantId);
        }
    }

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
