package com.socp.detect.web.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.Severity;
import com.socp.rule.model.SecurityEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentAlertSinkTest {

    @Test
    void keepsABoundedNewestFirstWriteOptimizedWindowAndSuppressesDuplicates() {
        RecentAlertSink sink = new RecentAlertSink(10, null, null);
        List<Alert> alerts = IntStream.range(0, 20)
                .mapToObj(this::alert)
                .toList();

        sink.publish(null, alerts);
        sink.publish(alerts.getLast());

        assertEquals(10, sink.recent().size());
        assertEquals("alert-10", sink.recent().getFirst().id());
        assertEquals("alert-19", sink.recent().getLast().id());
    }

    @Test
    void shadowModeSuppressesForwardingAndLiveBroadcastButStillComputesAndRunsTheGuard() {
        RecordingHub hub = new RecordingHub();
        java.util.concurrent.atomic.AtomicInteger guards =
                new java.util.concurrent.atomic.AtomicInteger();
        SecurityEvent event = new SecurityEvent("ev-1", Instant.EPOCH, "auth", "host-1", "msg",
                java.util.Map.of("tenant_id", "tenant-a"), Severity.HIGH);
        Alert shadowAlert = new Alert("alert-shadow", Instant.EPOCH, "rule-1", "Rule",
                Severity.HIGH, "message", "entity", List.of(event));
        Alert primaryAlert = new Alert("alert-primary", Instant.EPOCH, "rule-1", "Rule",
                Severity.HIGH, "message", "entity", List.of(event));

        RecentAlertSink shadow = new RecentAlertSink(10, null, hub, "shadow");
        shadow.publish(new com.socp.rule.engine.DetectionResult(event, null, java.util.Map.of(),
                List.of(), List.of(shadowAlert), List.of(shadowAlert), null, "idem-1"),
                guards::incrementAndGet);
        assertEquals(1, guards.get(), "shadow must still journal/checkpoint via the guard");
        assertEquals(0, hub.broadcasts, "shadow must not push phantom alerts onto the SSE stream");
        assertEquals(1, shadow.recent().size(), "shadow still computes the alert view");

        RecentAlertSink primary = new RecentAlertSink(10, null, hub, "primary");
        primary.publish(new com.socp.rule.engine.DetectionResult(event, null, java.util.Map.of(),
                List.of(), List.of(primaryAlert), List.of(primaryAlert), null, "idem-2"),
                guards::incrementAndGet);
        assertEquals(2, guards.get());
        assertEquals(1, hub.broadcasts, "the formal output path broadcasts exactly once");
    }

    private static class RecordingHub extends AlertStreamHub {
        int broadcasts;

        @Override
        public void broadcast(String tenant, Alert alert) {
            broadcasts++;
        }
    }

    private Alert alert(int index) {
        return new Alert("alert-" + index, Instant.EPOCH.plusSeconds(index),
                "rule-1", "Rule", Severity.HIGH, "message", "entity", List.of());
    }
}
