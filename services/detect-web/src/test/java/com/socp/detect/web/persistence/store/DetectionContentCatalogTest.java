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

    @Test
    void displayDefaultsDoNotClaimPackageOwnershipForUserRulesOrCollidingIds() {
        for (String id : List.of("custom-rule", "AUTH-BRUTE")) {
            Map<String, Object> enriched = DetectionContentCatalog.enrich(Map.of("id", id, "type", "pattern"));
            assertFalse(enriched.containsKey("contentPack"));
            assertFalse(enriched.containsKey("contentVersion"));
        }
        Map<String, Object> persisted = DetectionContentCatalog.enrich(Map.of("id", "AUTH-BRUTE", "type", "pattern",
                "contentPack", "socp-core-detections", "contentVersion", "2026.01.01"));
        assertEquals("socp-core-detections", persisted.get("contentPack"));
        assertEquals("2026.01.01", persisted.get("contentVersion"));
    }

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
            for (String field : List.of("id", "version", "owner", "dataSources", "mitre", "spec", "tests",
                    "description", "investigationGuide", "falsePositives")) {
                assertTrue(item.containsKey(field), () -> item.get("id") + " missing " + field);
            }
            // The investigation guide is what an analyst follows when the rule
            // fires; without it the rule is content, not guidance.
            assertFalse(String.valueOf(item.get("investigationGuide")).isBlank(),
                    () -> item.get("id") + " has a blank investigation guide");
            @SuppressWarnings("unchecked")
            List<String> falsePositives = (List<String>) item.get("falsePositives");
            assertFalse(falsePositives == null || falsePositives.stream().anyMatch(String::isBlank),
                    () -> item.get("id") + " must declare its false-positive patterns");
            @SuppressWarnings("unchecked")
            List<String> dataSources = (List<String>) item.get("dataSources");
            assertFalse(dataSources == null || dataSources.isEmpty(),
                    () -> item.get("id") + " must declare its data sources");
            @SuppressWarnings("unchecked")
            List<String> mitre = (List<String>) item.get("mitre");
            assertFalse(mitre == null || mitre.isEmpty(), () -> item.get("id") + " must map to ATT&CK");
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) item.get("spec");
            Map<String, Object> enriched = DetectionContentCatalog.enrich(spec);
            assertTrue(DetectionContentCatalog.validateSpec(enriched).isEmpty(), String.valueOf(item.get("id")));
            String type = String.valueOf(enriched.get("type"));
            if (List.of("threshold", "correlation", "correlation-set", "baseline", "rare")
                    .contains(type.toLowerCase())) {
                assertFalse(String.valueOf(enriched.getOrDefault("groupBy", "")).isBlank(),
                        String.valueOf(item.get("id")) + " must declare groupBy");
                assertTrue(enriched.containsKey("lateEventPolicy"),
                        String.valueOf(item.get("id")) + " must declare lateEventPolicy");
            }
            assertEquals("ACTIVE".equalsIgnoreCase(String.valueOf(enriched.get("status"))),
                    enriched.get("enabled"), String.valueOf(item.get("id")) + " lifecycle projection");
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
                .anyMatch(error -> error.contains("cross-entity grouping is unsupported")
                        && error.contains("keyField and routingField must match")));

        Map<String, Object> explicit = new LinkedHashMap<>(legacy);
        explicit.remove("keyField");
        explicit.put("groupBy", "host");
        explicit.put("routingField", "host");
        explicit.put("lateEventPolicy", Map.of("allowedLateness", "10s", "handling", "DROP"));
        assertTrue(DetectionContentCatalog.validateSpec(explicit).isEmpty());

        Map<String, Object> mismatchedGrouping = new LinkedHashMap<>(explicit);
        mismatchedGrouping.put("keyField", "user");
        assertTrue(DetectionContentCatalog.validateSpec(mismatchedGrouping).stream()
                .anyMatch(error -> error.contains("groupBy and keyField")));
    }

    @Test
    void windowIsValidationCheckedWithTheOneDurationGrammarTheEngineUses() {
        Map<String, Object> valid = new LinkedHashMap<>();
        valid.put("id", "win");
        valid.put("name", "win");
        valid.put("type", "threshold");
        valid.put("severity", "HIGH");
        valid.put("version", "1");
        valid.put("owner", "test");
        valid.put("groupBy", "host");
        valid.put("routingField", "host");
        valid.put("threshold", 2);

        // "7d" and "500ms" used to pass validation and then throw while the
        // engine was assembled, which stopped detection for the whole tenant.
        for (String window : List.of("7d", "500ms", "PT10M", "2h", "45")) {
            Map<String, Object> candidate = new LinkedHashMap<>(valid);
            candidate.put("window", window);
            assertTrue(DetectionContentCatalog.validateSpec(candidate).isEmpty(), window);
            new RuleSpec(candidate).toRule().close();
        }

        for (String garbage : List.of("weekly", "1w", "0s", "-5m", "60 s")) {
            Map<String, Object> candidate = new LinkedHashMap<>(valid);
            candidate.put("window", garbage);
            List<String> errors = DetectionContentCatalog.validateSpec(candidate);
            assertTrue(errors.stream().anyMatch(error -> error.startsWith("invalid window")), garbage);
        }
    }

    @Test
    void regexValidationUsesTheSameLinearTimeSubsetAsRuntime() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("id", "regex-validation");
        base.put("name", "regex-validation");
        base.put("type", "pattern");
        base.put("severity", "HIGH");
        base.put("version", "1");
        base.put("owner", "test");

        Map<String, Object> safe = new LinkedHashMap<>(base);
        safe.put("match", List.of(Map.of(
                "field", "msg", "op", "regex", "value", "(?i)(useradd|adduser).*(sudo|wheel|admin)")));
        assertTrue(DetectionContentCatalog.validateSpec(safe).isEmpty());

        Map<String, Object> backreference = new LinkedHashMap<>(base);
        backreference.put("match", List.of(Map.of(
                "field", "msg", "op", "regex", "value", "(a+)\\1")));
        assertTrue(DetectionContentCatalog.validateSpec(backreference).stream()
                .anyMatch(error -> error.contains("invalid or unsupported regex")));

        Map<String, Object> oversized = new LinkedHashMap<>(base);
        oversized.put("match", List.of(Map.of(
                "field", "msg", "op", "regex", "value", "a".repeat(2_049))));
        assertTrue(DetectionContentCatalog.validateSpec(oversized).stream()
                .anyMatch(error -> error.contains("exceeds 2048 characters")));
    }

    @Test
    void partitionLocalAdvisoriesNameTheDimensionThatOutranksTheGrouping() {
        Map<String, Object> userGrouped = new LinkedHashMap<>();
        userGrouped.put("id", "advisory");
        userGrouped.put("type", "threshold");
        userGrouped.put("groupBy", "user");
        userGrouped.put("dataSources", List.of("auth", "linux"));

        List<String> advisories = DetectionContentCatalog.partitionLocalAdvisories(userGrouped);
        assertEquals(2, advisories.size(), "每个可被更高优先级维度压过的数据源各一条");
        assertTrue(advisories.stream().anyMatch(text -> text.contains("'auth' route by 'src_ip'")));
        assertTrue(advisories.stream().anyMatch(text -> text.contains("'linux' route by 'host'")));

        userGrouped.put("groupBy", "src_ip");
        userGrouped.put("dataSources", List.of("auth"));
        assertTrue(DetectionContentCatalog.partitionLocalAdvisories(userGrouped).isEmpty());

        Map<String, Object> stateless = new LinkedHashMap<>(userGrouped);
        stateless.put("type", "pattern");
        assertTrue(DetectionContentCatalog.partitionLocalAdvisories(stateless).isEmpty(),
                "无状态规则没有分区本地状态可讨论");
        assertTrue(DetectionContentCatalog.partitionLocalAdvisories(null).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void packagedContentAdvisoriesNeverTurnIntoRejections() {
        List<Map<String, Object>> rules =
                (List<Map<String, Object>>) DetectionContentCatalog.manifest().get("rules");
        int advised = 0;
        for (Map<String, Object> item : rules) {
            Map<String, Object> enriched = DetectionContentCatalog.enrich(
                    (Map<String, Object>) item.get("spec"));
            // The advisory is deliberately not part of validateSpec: rejecting it
            // would reject shipped content that legitimately correlates across
            // sources, so the trade-off is measured instead of refused here.
            assertTrue(DetectionContentCatalog.validateSpec(enriched).isEmpty(),
                    String.valueOf(item.get("id")));
            if (!DetectionContentCatalog.partitionLocalAdvisories(enriched).isEmpty()) advised++;
        }
        assertTrue(advised > 0, "内容包里的 user 维度分组规则应被点名，否则该审计无证据");
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
