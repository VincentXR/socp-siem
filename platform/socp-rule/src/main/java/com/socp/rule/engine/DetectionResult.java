package com.socp.rule.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of evaluating one canonical input event.
 *
 * <p>The result is the hand-off between rule calculation and durable result
 * commit. It carries the input position, executable rule versions, a
 * digest-only state diff, suppression information and a stable idempotency
 * key. State bytes and raw input remain in their respective recovery stores;
 * this envelope is safe to put on an alert outbox or an audit record.</p>
 */
public record DetectionResult(
        SecurityEvent event,
        InputPosition inputPosition,
        Map<String, String> ruleVersions,
        List<StateChange> stateChanges,
        List<Alert> candidates,
        List<Alert> alerts,
        SuppressionDecision suppression,
        String idempotencyKey) {

    public DetectionResult {
        Objects.requireNonNull(event, "event is required");
        inputPosition = inputPosition == null ? InputPosition.unknown() : inputPosition;
        ruleVersions = immutableMap(ruleVersions);
        stateChanges = stateChanges == null ? List.of() : List.copyOf(stateChanges);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
        suppression = suppression == null
                ? SuppressionDecision.none(candidates, alerts) : suppression;
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? event.scopedId() : idempotencyKey;
    }

    public boolean hasAlerts() {
        return !alerts.isEmpty();
    }

    public int suppressedCount() {
        return suppression.suppressedAlertIds().size();
    }

    /** Compact, raw-payload-free representation suitable for a journal column. */
    public Map<String, Object> auditSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputEventId", event.id());
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("topic", inputPosition.topic());
        position.put("partition", inputPosition.partition());
        position.put("offset", inputPosition.offset());
        out.put("inputPosition", position);
        out.put("ruleVersions", ruleVersions);
        out.put("stateChanges", stateChanges.stream().map(change -> Map.of(
                "ruleId", change.ruleId(),
                "beforeDigest", change.beforeDigest(),
                "afterDigest", change.afterDigest(),
                "changed", change.changed())).toList());
        out.put("candidateAlertIds", alertIds(candidates));
        out.put("emittedAlertIds", alertIds(alerts));
        out.put("suppression", Map.of(
                "policy", suppression.policy(),
                "candidateCount", suppression.candidateCount(),
                "emittedCount", suppression.emittedCount(),
                "suppressedAlertIds", suppression.suppressedAlertIds()));
        out.put("idempotencyKey", idempotencyKey);
        return Map.copyOf(out);
    }

    private static List<String> alertIds(List<Alert> source) {
        if (source == null || source.isEmpty()) return List.of();
        return source.stream().filter(Objects::nonNull).map(Alert::id)
                .filter(Objects::nonNull).toList();
    }

    private static Map<String, String> immutableMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !key.isBlank()) copy.put(key, value == null ? "" : value);
        });
        return Map.copyOf(copy);
    }

    /** Kafka input identity; unknown values are represented by -1. */
    public record InputPosition(String topic, int partition, long offset) {
        public InputPosition {
            topic = topic == null || topic.isBlank() ? null : topic.trim();
            if (partition < -1) throw new IllegalArgumentException("partition must be -1 or non-negative");
            if (offset < -1) throw new IllegalArgumentException("offset must be -1 or non-negative");
            if ((partition == -1) != (offset == -1)) {
                throw new IllegalArgumentException("partition and offset must be known together");
            }
        }

        public static InputPosition unknown() {
            return new InputPosition(null, -1, -1);
        }

        public boolean known() {
            return partition >= 0 && offset >= 0;
        }
    }

    /** Digest-only before/after state evidence; state payloads stay in snapshots. */
    public record StateChange(String ruleId, String beforeDigest, String afterDigest, boolean changed) {
        public StateChange {
            if (ruleId == null || ruleId.isBlank()) throw new IllegalArgumentException("ruleId is required");
            beforeDigest = normalizeDigest(beforeDigest);
            afterDigest = normalizeDigest(afterDigest);
        }

        private static String normalizeDigest(String digest) {
            return digest == null || digest.isBlank() ? "none" : digest;
        }
    }

    /** Result of suppression reservation, including the alert ids held back. */
    public record SuppressionDecision(String policy, int candidateCount, int emittedCount,
                                      List<String> suppressedAlertIds) {
        public SuppressionDecision {
            policy = policy == null || policy.isBlank() ? "NONE" : policy;
            if (candidateCount < 0 || emittedCount < 0 || emittedCount > candidateCount) {
                throw new IllegalArgumentException("invalid suppression counts");
            }
            suppressedAlertIds = suppressedAlertIds == null
                    ? List.of() : suppressedAlertIds.stream()
                    .filter(id -> id != null && !id.isBlank()).distinct().toList();
        }

        public static SuppressionDecision none(List<Alert> candidates, List<Alert> alerts) {
            return new SuppressionDecision("NONE", size(candidates), size(alerts), List.of());
        }

        public static SuppressionDecision window(List<Alert> candidates, List<Alert> alerts) {
            java.util.Set<String> emitted = alerts == null ? java.util.Set.of() : alerts.stream()
                    .filter(Objects::nonNull)
                    .map(Alert::id)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            List<String> suppressed = new ArrayList<>();
            if (candidates != null) {
                for (Alert alert : candidates) {
                    if (alert != null && alert.id() != null && !emitted.contains(alert.id())) {
                        suppressed.add(alert.id());
                    }
                }
            }
            return new SuppressionDecision("WINDOW", size(candidates), size(alerts), suppressed);
        }

        private static int size(List<?> values) {
            return values == null ? 0 : values.size();
        }
    }

    /** Stable SHA-256 digest helper for the rule engine's state-diff builder. */
    public static String digest(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "none";
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("unable to digest detection state", failure);
        }
    }

    /** Trace-safe event time accessor for adapters serializing the result. */
    public Instant eventTimestamp() {
        return event.timestamp();
    }
}
