package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.socp.rule.config.RuleSpec;
import com.socp.rule.engine.AlertSink;
import com.socp.rule.engine.RuleEngine;
import com.socp.rule.engine.Watchlists;
import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes every versioned manifest vector against the real Java rule engine. */
class DetectionContentExecutionTest {

    @AfterEach
    void clearWatchlists() {
        Watchlists.clear();
    }

    @Test
    void everyManifestRulePassesPositiveAndNegativeVectors() throws Exception {
        Watchlists.put("blocked_ips", List.of("10.0.0.66"));
        Watchlists.put("high_risk_entities", List.of("HIGH", "CRITICAL"));
        Watchlists.put("privileged_accounts", List.of("root", "domain-admin"));
        Watchlists.put("crown_jewels", List.of("10.0.0.10"));

        Object rawRules = DetectionContentCatalog.manifest().get("rules");
        assertTrue(rawRules instanceof List<?>);
        for (Object item : (List<?>) rawRules) {
            assertTrue(item instanceof Map<?, ?>);
            Map<?, ?> ruleItem = (Map<?, ?>) item;
            String ruleId = String.valueOf(ruleItem.get("id"));
            Map<String, Object> specMap = objectMap(ruleItem.get("spec"));
            RuleSpec spec = new RuleSpec(specMap);
            List<?> vectors = (List<?>) ruleItem.get("tests");
            assertFalse(vectors.isEmpty(), ruleId + " has no vectors");

            for (Object rawVector : vectors) {
                Map<?, ?> vector = (Map<?, ?>) rawVector;
                boolean expected = Boolean.TRUE.equals(vector.get("expectAlert"));
                List<SecurityEvent> events = events(vector.get("events"));
                CollectingSink sink = new CollectingSink();
                try (RuleEngine engine = new RuleEngine(List.of(spec.toRule()), List.of(sink))) {
                    engine.start();
                    events.forEach(engine::ingest);
                    awaitEvents(engine, events.size());
                    Thread.sleep(30);
                }
                assertEquals(expected, !sink.alerts.isEmpty(),
                        ruleId + " vector=" + vector.get("name"));
            }
        }
    }

    private static void awaitEvents(RuleEngine engine, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (engine.eventCount() < expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(expected, engine.eventCount(), "rule worker did not drain vector");
    }

    private static List<SecurityEvent> events(Object raw) {
        List<SecurityEvent> out = new ArrayList<>();
        for (Object value : (List<?>) raw) {
            Map<?, ?> map = (Map<?, ?>) value;
            int repeat = map.get("repeat") instanceof Number number ? number.intValue() : 1;
            for (int occurrence = 0; occurrence < repeat; occurrence++) {
            Map<String, String> fields = new LinkedHashMap<>();
            Object rawFields = map.get("fields");
            if (rawFields instanceof Map<?, ?> fieldMap) {
                fieldMap.forEach((key, item) -> fields.put(String.valueOf(key), String.valueOf(item)));
            }
            Object rawMessage = map.get("msg");
            if (rawMessage == null) rawMessage = map.get("message");
            String msg = rawMessage == null ? "" : String.valueOf(rawMessage);
            fields.putIfAbsent("msg", msg);
            Object rawSource = map.get("source");
            Object rawHost = map.get("host");
            String source = rawSource == null ? "unknown" : String.valueOf(rawSource);
            String host = rawHost == null ? "test-host" : String.valueOf(rawHost);
            Object rawTimestamp = map.get("timestamp");
            Instant timestamp = rawTimestamp == null
                    ? Instant.now()
                    : Instant.parse(String.valueOf(rawTimestamp));
            out.add(new SecurityEvent(UUID.randomUUID().toString(), timestamp, source, host,
                    msg, fields, Severity.INFO));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static final class CollectingSink implements AlertSink {
        private final List<Alert> alerts = new ArrayList<>();

        @Override
        public synchronized void publish(Alert alert) {
            alerts.add(alert);
        }

        @Override
        public void close() {
        }
    }
}
