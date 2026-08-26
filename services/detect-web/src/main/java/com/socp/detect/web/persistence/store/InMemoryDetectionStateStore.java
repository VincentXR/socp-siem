package com.socp.detect.web.persistence.store;



import com.socp.detect.web.persistence.store.*;
import com.socp.detect.web.persistence.repository.*;
import com.socp.detect.web.persistence.entity.*;
import com.socp.rule.model.SecurityEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Small fallback used by unit tests that construct the service directly. */
public final class InMemoryDetectionStateStore implements DetectionStateStore {

    private final Map<String, Entry> events = new ConcurrentHashMap<>();

    @Override
    public DetectionEventClaim claim(SecurityEvent event) {
        return claim(event, null, null, null);
    }

    @Override
    public DetectionEventClaim claim(SecurityEvent event, Integer partition, Long offset,
                                     String routingKey) {
        if (event == null || event.id() == null || event.id().isBlank()) {
            throw new IllegalArgumentException("event id is required");
        }
        event.requireTenantId();
        Entry existing = events.get(event.scopedId());
        if (existing != null) return existing.status() == DetectionEventStatus.PENDING
                ? DetectionEventClaim.PENDING
                : existing.status() == DetectionEventStatus.DEAD_LETTERED
                ? DetectionEventClaim.DEAD_LETTERED : DetectionEventClaim.COMPLETED;
        Entry created = new Entry(event, partition, offset, routingKey, DetectionEventStatus.PENDING);
        Entry raced = events.putIfAbsent(event.scopedId(), created);
        return raced == null ? DetectionEventClaim.NEW
                : raced.status() == DetectionEventStatus.PENDING
                ? DetectionEventClaim.PENDING : DetectionEventClaim.COMPLETED;
    }

    @Override
    public boolean recordIfNew(SecurityEvent event) {
        return claim(event) == DetectionEventClaim.NEW;
    }

    @Override
    public boolean recordIfNew(SecurityEvent event, Integer partition, Long offset, String routingKey) {
        return claim(event, partition, offset, routingKey) == DetectionEventClaim.NEW;
    }

    @Override
    public void markCompleted(String eventId) {
        markCompleted(com.socp.platform.tenant.context.TenantContext.require(), eventId);
    }

    @Override
    public void markCompleted(String tenantId, String eventId) {
        events.computeIfPresent(tenantId + "|" + eventId,
                (id, entry) -> entry.withStatus(DetectionEventStatus.COMPLETED));
    }

    @Override
    public void markDeadLettered(String eventId, String reason) {
        markDeadLettered(com.socp.platform.tenant.context.TenantContext.require(), eventId, reason);
    }

    @Override
    public void markDeadLettered(String tenantId, String eventId, String reason) {
        events.computeIfPresent(tenantId + "|" + eventId,
                (id, entry) -> entry.withStatus(DetectionEventStatus.DEAD_LETTERED));
    }

    @Override
    public void recordDeadLettered(String eventId, String raw, Integer partition, Long offset,
                                   String reason) {
        if (eventId == null || eventId.isBlank() || "null".equalsIgnoreCase(eventId)) return;
        String tenant = com.socp.platform.tenant.context.TenantContext.get();
        if (tenant == null || tenant.isBlank()) return;
        events.putIfAbsent(tenant + "|" + eventId, new Entry(
                new SecurityEvent(eventId, Instant.now(), "unknown", "unknown", raw,
                        Map.of("tenant_id", tenant), com.socp.rule.model.Severity.INFO),
                partition, offset, null, DetectionEventStatus.DEAD_LETTERED));
        markDeadLettered(eventId, reason);
    }

    @Override
    public void remove(String eventId) {
        remove(com.socp.platform.tenant.context.TenantContext.require(), eventId);
    }

    @Override
    public void remove(String tenantId, String eventId) {
        if (eventId != null) events.remove(tenantId + "|" + eventId);
    }

    @Override
    public List<SecurityEvent> recent(Duration window) {
        return events.values().stream()
                .filter(e -> e.status() == DetectionEventStatus.COMPLETED)
                .map(Entry::event)
                .filter(e -> !e.timestamp().isBefore(Instant.now().minus(window)))
                .sorted(Comparator.comparing(SecurityEvent::timestamp))
                .toList();
    }

    @Override
    public List<SecurityEvent> recentForPartitions(Set<Integer> partitions, Duration window) {
        return events.values().stream()
                .filter(e -> e.status() == DetectionEventStatus.COMPLETED
                        && e.partition() != null && partitions.contains(e.partition()))
                .map(Entry::event)
                .filter(e -> !e.timestamp().isBefore(Instant.now().minus(window)))
                .sorted(Comparator.comparing(SecurityEvent::timestamp))
                .toList();
    }

    @Override
    public List<SecurityEvent> pendingForPartitions(Set<Integer> partitions, Duration window) {
        return events.values().stream()
                .filter(e -> e.status() == DetectionEventStatus.PENDING
                        && e.partition() != null && partitions.contains(e.partition()))
                .map(Entry::event)
                .filter(e -> !e.timestamp().isBefore(Instant.now().minus(window)))
                .sorted(Comparator.comparing(SecurityEvent::timestamp))
                .toList();
    }

    @Override
    public List<PendingDetectionEvent> pendingRecordsForPartitions(Set<Integer> partitions,
                                                                    Duration window) {
        return events.values().stream()
                .filter(e -> e.status() == DetectionEventStatus.PENDING
                        && e.partition() != null && partitions.contains(e.partition()))
                .filter(e -> !e.event().timestamp().isBefore(Instant.now().minus(window)))
                .sorted(Comparator.comparing(e -> e.event().timestamp()))
                .map(e -> new PendingDetectionEvent(e.event(), e.partition(), e.offset()))
                .toList();
    }

    @Override
    public long pendingCount() {
        return events.values().stream().filter(e -> e.status() == DetectionEventStatus.PENDING).count();
    }

    @Override
    public long pendingCount(String tenantId) {
        return events.values().stream()
                .filter(e -> e.status() == DetectionEventStatus.PENDING
                        && tenantId.equals(e.event().tenantId()))
                .count();
    }

    @Override
    public String recoveryWindow() {
        return "test";
    }

    private record Entry(SecurityEvent event, Integer partition, Long offset,
                         String routingKey, DetectionEventStatus status) {
        Entry withStatus(DetectionEventStatus next) {
            return new Entry(event, partition, offset, routingKey, next);
        }
    }
}
