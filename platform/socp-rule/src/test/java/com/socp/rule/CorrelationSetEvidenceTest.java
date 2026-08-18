package com.socp.rule;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.CorrelationSetRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorrelationSetEvidenceTest {

    @Test
    void includesAllEventsThatCompleteTheCorrelation() {
        CorrelationSetRule rule = new CorrelationSetRule(
                "AUTH-BRUTE-SUCCESS", "Failed then accepted login",
                event -> event.get("src_ip"),
                List.of(
                        event -> "failed".equals(event.get("phase")),
                        event -> "accepted".equals(event.get("phase"))),
                Duration.ofMinutes(5), Severity.HIGH, "correlated {key}");

        SecurityEvent failed = event("event-failed", "failed");
        SecurityEvent accepted = event("event-accepted", "accepted");
        rule.accept(failed);
        rule.accept(accepted);

        List<Alert> alerts = rule.drain();

        assertEquals(1, alerts.size());
        assertEquals(List.of("event-failed", "event-accepted"),
                alerts.get(0).evidence().stream().map(SecurityEvent::id).toList());
    }

    private static SecurityEvent event(String id, String phase) {
        return new SecurityEvent(id, Instant.parse("2026-08-18T10:00:00Z"), "auth", "host-1",
                phase + " login", Map.of("src_ip", "10.0.0.9", "phase", phase), Severity.INFO);
    }
}
