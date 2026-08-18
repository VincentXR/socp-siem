package com.socp.detect.web.store;

import com.socp.rule.model.SecurityEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small test-only fallback used by unit tests that construct the service directly. */
public final class InMemoryDetectionStateStore implements DetectionStateStore {

    private final Map<String, SecurityEvent> events = new ConcurrentHashMap<>();

    @Override
    public boolean recordIfNew(SecurityEvent event) {
        return events.putIfAbsent(event.id(), event) == null;
    }

    @Override
    public void remove(String eventId) {
        if (eventId != null) events.remove(eventId);
    }

    @Override
    public List<SecurityEvent> recent(Duration window) {
        Instant cutoff = Instant.now().minus(window);
        List<SecurityEvent> out = new ArrayList<>();
        for (SecurityEvent event : events.values()) {
            if (!event.timestamp().isBefore(cutoff)) out.add(event);
        }
        out.sort(Comparator.comparing(SecurityEvent::timestamp));
        return out;
    }

    @Override
    public String recoveryWindow() {
        return "test";
    }
}
