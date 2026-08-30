package com.socp.detect.web.persistence.store;


import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the versioned content pack and its positive/negative vectors. */
class DetectionContentCatalogTest {

    @BeforeEach
    void seedContentWatchlists() {
        Watchlists.put("blocked_ips", List.of("10.0.0.66"));
        Watchlists.put("high_risk_entities", List.of("HIGH", "CRITICAL"));
        Watchlists.put("privileged_accounts", List.of("root", "domain-admin"));
        Watchlists.put("crown_jewels", List.of("10.0.0.10"));
    }

    @AfterEach
    void clearContentWatchlists() {
        Watchlists.clear();
    }

    @Test
    void everyPackRuleHasMetadataAndCounterexamples() {
        Map<String, Object> manifest = DetectionContentCatalog.manifest();
        assertEquals("socp-core-detections", manifest.get("packId"));
        assertFalse(String.valueOf(manifest.get("version")).isBlank());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) manifest.get("rules");
        assertEquals(39, rules.size(), "content loss must fail the catalog contract");
        for (Map<String, Object> item : rules) {
            for (String field : List.of("id", "version", "owner", "dataSources", "mitre", "spec", "tests")) {
                assertTrue(item.containsKey(field), () -> item.get("id") + " missing " + field);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) item.get("spec");
            assertTrue(DetectionContentCatalog.validateSpec(
                    DetectionContentCatalog.enrich(spec)).isEmpty(), String.valueOf(item.get("id")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tests = (List<Map<String, Object>>) item.get("tests");
            assertTrue(tests.stream().anyMatch(t -> Boolean.TRUE.equals(t.get("expectAlert"))));
            assertTrue(tests.stream().anyMatch(t -> Boolean.FALSE.equals(t.get("expectAlert"))));
        }
    }

    @Test
    void positiveAndNegativeVectorsMatchExecutableRules() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) DetectionContentCatalog.manifest().get("rules");
        for (Map<String, Object> item : rules) {
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) item.get("spec");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tests = (List<Map<String, Object>>) item.get("tests");
            for (Map<String, Object> vector : tests) {
                Rule rule = new RuleSpec(spec).toRule();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> events = (List<Map<String, Object>>) vector.get("events");
                int index = 0;
                for (Map<String, Object> input : events) {
                    int repeat = input.get("repeat") instanceof Number number ? number.intValue() : 1;
                    for (int occurrence = 0; occurrence < repeat; occurrence++) {
                        rule.accept(toEvent(input, index++));
                    }
                }
                boolean alerted = !rule.drain().isEmpty();
                assertEquals(Boolean.TRUE.equals(vector.get("expectAlert")), alerted,
                        () -> item.get("id") + ":" + vector.get("name"));
                rule.close();
            }
        }
    }

    @Test
    void statefulRulesDeclareTheSameGroupingAndKafkaRoutingDimension() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("id", "legacy-threshold");
        legacy.put("name", "legacy");
        legacy.put("type", "threshold");
        legacy.put("severity", "HIGH");
        legacy.put("version", "1");
        legacy.put("owner", "test");
        legacy.put("keyField", "host");
        legacy.put("threshold", 2);
        assertEquals("host", DetectionContentCatalog.enrich(legacy).get("routingField"));

        Map<String, Object> invalid = new LinkedHashMap<>(legacy);
        invalid.put("routingField", "user");
        assertTrue(DetectionContentCatalog.validateSpec(invalid).stream()
                .anyMatch(error -> error.contains("keyField and routingField must match")));
    }

    @SuppressWarnings("unchecked")
    private static SecurityEvent toEvent(Map<String, Object> input, int index) {
        Map<String, String> fields = new LinkedHashMap<>();
        Object rawFields = input.get("fields");
        if (rawFields instanceof Map<?, ?> map) {
            map.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        }
        fields.put("msg", String.valueOf(input.getOrDefault("msg", "")));
        Object rawTimestamp = input.get("timestamp");
        Instant timestamp = rawTimestamp == null
                ? Instant.parse("2026-01-01T00:00:0" + Math.min(index, 9) + "Z")
                : Instant.parse(String.valueOf(rawTimestamp));
        return new SecurityEvent("content-test-" + index + "-" + input.hashCode(),
                timestamp,
                String.valueOf(input.getOrDefault("source", "unknown")),
                String.valueOf(input.getOrDefault("host", "unknown")),
                String.valueOf(input.getOrDefault("msg", "")), fields, Severity.HIGH);
    }
}
