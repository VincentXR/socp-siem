package com.socp.detect.web.store;

import com.socp.rule.model.SecurityEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small test-only fallback used by unit tests that construct the service directly. */
public final class InMemoryDetectionStateStore implements DetectionStateStore {

    private final Map<String, Entry> events = new ConcurrentHashMap<>();

    @Override
    public boolean recordIfNew(SecurityEvent event) {
        return recordIfNew(event, null, null, null);
    }

    @Override
    public boolean recordIfNew(SecurityEvent event, Integer partition, Long offset, String routingKey) {
        return event != null && events.putIfAbsent(event.id(), new Entry(event, partition)) == null;
    }

    @Override
    public void remove(String eventId) {
        if (eventId != null) events.remove(eventId);
    }

    @Override
    public List<SecurityEvent> recent(Duration window) {
        Instant cutoff = Instant.now().minus(window);
        List<SecurityEvent> out = new ArrayList<>();
        for (Entry entry : events.values()) {
            SecurityEvent event = entry.event();
            if (!event.timestamp().isBefore(cutoff)) out.add(event);
        }
        out.sort(Comparator.comparing(SecurityEvent::timestamp));
        return out;
    }

    @Override
    public List<SecurityEvent> recentForPartitions(Set<Integer> partitions, Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        Instant cutoff = Instant.now().minus(window);
        List<SecurityEvent> out = new ArrayList<>();
        for (Entry entry : events.values()) {
            if (entry.partition() != null && partitions.contains(entry.partition())
                    && !entry.event().timestamp().isBefore(cutoff)) {
                out.add(entry.event());
            }
        }
        out.sort(Comparator.comparing(SecurityEvent::timestamp));
        return out;
    }

    @Override
    public String recoveryWindow() {
        return "test";
    }

    private record Entry(SecurityEvent event, Integer partition) {
    }
}
