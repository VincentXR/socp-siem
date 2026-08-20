package com.socp.detect.web.engine;

import com.socp.rule.model.Alert;
import com.socp.rule.model.Severity;
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

    private Alert alert(int index) {
        return new Alert("alert-" + index, Instant.EPOCH.plusSeconds(index),
                "rule-1", "Rule", Severity.HIGH, "message", "entity", List.of());
    }
}
