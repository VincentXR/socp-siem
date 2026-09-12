package com.socp.rule;

import com.socp.rule.config.RuleSpec;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.BaselineRule;
import com.socp.rule.util.Json;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class BaselineRuleGapTest {

    @Test
    void farFutureTimestampDoesNotMaterializeEverySkippedBucket() {
        BaselineRule rule = (BaselineRule) new RuleSpec(Json.parseObject("""
                {"id":"B-gap","name":"baseline gap","type":"baseline","severity":"HIGH",
                 "message":"{key}","keyField":"src_ip","window":"1s",
                 "baselineWindows":12,"warmup":3,"sigma":3.0,"minCount":5,"match":[]}
                """)).toRule();

        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        rule.accept(event(start));

        assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> rule.accept(event(start.plusSeconds(50_000_000L))));

        Map<String, Object> row = rule.snapshot().getFirst();
        assertEquals(12, row.get("samples"));
        assertEquals(0.0, row.get("baseline"));
        assertEquals(1, row.get("current"));
    }

    private static SecurityEvent event(Instant timestamp) {
        return new SecurityEvent(timestamp, "auth", "host-1", "raw",
                Map.of("src_ip", "10.0.0.1"), Severity.INFO);
    }
}
