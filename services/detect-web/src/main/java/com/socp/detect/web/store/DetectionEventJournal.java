package com.socp.detect.web.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * PostgreSQL/H2-backed journal for Detection event claims and state recovery.
 *
 * <p>Only COMPLETED events rebuild hot rule windows. PENDING rows are replayed
 * as live work after a restart/rebalance, while DEAD_LETTERED rows are
 * terminal and never silently re-enter the detection engine.</p>
 */
@Component
public class DetectionEventJournal implements DetectionStateStore {

    private static final Logger log = LoggerFactory.getLogger(DetectionEventJournal.class);
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};

    private final DetectionEventRepository repository;
    private final Duration retention;
    private final int replayPageSize;

    public DetectionEventJournal(DetectionEventRepository repository,
                                 @Value("${socp.detect.state.retention:24h}") String retention,
                                 @Value("${socp.detect.state.replay-page-size:1000}") int replayPageSize) {
        this.repository = repository;
        this.retention = parseDuration(retention);
        this.replayPageSize = Math.max(100, Math.min(10_000, replayPageSize));
    }

    @Override
    @Transactional
    public DetectionEventClaim claim(SecurityEvent event) {
        return claim(event, null, null, null);
    }

    @Override
    @Transactional
    public DetectionEventClaim claim(SecurityEvent event, Integer partition, Long offset,
                                     String routingKey) {
        if (event == null || event.id() == null || event.id().isBlank()) {
            throw new IllegalArgumentException("event id is required");
        }
        var existing = repository.findById(event.id());
        if (existing.isPresent()) return claimOf(existing.get());

        try {
            String fields = com.socp.rule.util.Json.mapper().writeValueAsString(
                    event.fields() == null ? Map.of() : event.fields());
            repository.saveAndFlush(new DetectionEventEntity(
                    event.id(), safe(event.source(), "unknown", 64),
                    safe(event.host(), "unknown", 255), safe(event.raw(), "", 8192),
                    fields, event.severity() == null ? Severity.INFO.name() : event.severity().name(),
                    event.timestamp() == null ? Instant.now() : event.timestamp(),
                    partition, offset, routingKey));
            return DetectionEventClaim.NEW;
        } catch (DataIntegrityViolationException duplicate) {
            // The primary key is the final arbiter when two consumers race.
            // The caller retries; the next transaction observes the durable row.
            throw duplicate;
        } catch (Exception ex) {
            throw new IllegalStateException("unable to write detection event journal: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean recordIfNew(SecurityEvent event) {
        return claim(event) == DetectionEventClaim.NEW;
    }

    @Override
    public boolean recordIfNew(SecurityEvent event, Integer partition, Long offset,
                               String routingKey) {
        return claim(event, partition, offset, routingKey) == DetectionEventClaim.NEW;
    }

    @Override
    @Transactional
    public void markCompleted(String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findById(eventId).ifPresent(row -> {
            if (DetectionEventStatus.DEAD_LETTERED.name().equals(row.getStatus())) return;
            Instant now = Instant.now();
            row.setStatus(DetectionEventStatus.COMPLETED.name());
            row.setCompletedAt(now);
            row.setStatusReason(null);
            repository.saveAndFlush(row);
        });
    }

    @Override
    @Transactional
    public void markDeadLettered(String eventId, String reason) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findById(eventId).ifPresent(row -> {
            Instant now = Instant.now();
            row.setStatus(DetectionEventStatus.DEAD_LETTERED.name());
            row.setDeadLetteredAt(now);
            row.setStatusReason(truncate(reason));
            repository.saveAndFlush(row);
        });
    }

    @Override
    @Transactional
    public void recordDeadLettered(String eventId, String raw, Integer partition, Long offset,
                                   String reason) {
        if (eventId == null || eventId.isBlank() || "null".equalsIgnoreCase(eventId)) return;
        var existing = repository.findById(eventId);
        if (existing.isPresent()) {
            markDeadLettered(eventId, reason);
            return;
        }
        DetectionEventEntity row = new DetectionEventEntity(
                eventId, "unknown", "unknown", safe(raw, "", 8192), "{}", Severity.INFO.name(),
                Instant.now(), partition, offset, null);
        row.setStatus(DetectionEventStatus.DEAD_LETTERED.name());
        row.setDeadLetteredAt(Instant.now());
        row.setStatusReason(truncate(reason));
        repository.saveAndFlush(row);
    }

    @Override
    @Transactional
    public void remove(String eventId) {
        if (eventId != null && !eventId.isBlank()) repository.deleteById(eventId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> recent(Duration window) {
        return readPages((page, size) -> repository
                .findByStatusAndOccurredAtAfterOrderByOccurredAtAscEventIdAsc(
                        DetectionEventStatus.COMPLETED.name(), cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> recentForPartitions(Set<Integer> partitions, Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        return readPages((page, size) -> repository
                .findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                        DetectionEventStatus.COMPLETED.name(), partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), false);
    }

    @Override
    public void replayRecent(Duration window, Consumer<List<SecurityEvent>> batchConsumer) {
        replayPages((page, size) -> repository
                .findByStatusAndOccurredAtAfterOrderByOccurredAtAscEventIdAsc(
                        DetectionEventStatus.COMPLETED.name(), cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    public void replayRecentForPartitions(Set<Integer> partitions, Duration window,
                                          Consumer<List<SecurityEvent>> batchConsumer) {
        if (partitions == null || partitions.isEmpty()) return;
        replayPages((page, size) -> repository
                .findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                        DetectionEventStatus.COMPLETED.name(), partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> pendingForPartitions(Set<Integer> partitions, Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        return readPages((page, size) -> repository
                .findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                        DetectionEventStatus.PENDING.name(), partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingDetectionEvent> pendingRecordsForPartitions(Set<Integer> partitions,
                                                                    Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        List<DetectionEventEntity> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<DetectionEventEntity> batch = repository
                    .findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                            DetectionEventStatus.PENDING.name(), partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, replayPageSize));
            rows.addAll(batch);
            if (batch.size() < replayPageSize) break;
        }
        return rows.stream().map(this::pendingRow).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount() {
        return repository.countByStatus(DetectionEventStatus.PENDING.name());
    }

    @Override
    public String recoveryWindow() {
        return retention.toString();
    }

    public Duration retention() {
        return retention;
    }

    /**
     * Keep retention maintenance out of the per-event claim transaction.
     * PENDING rows are deliberately excluded: they represent work whose Kafka
     * offset cannot advance and must remain recoverable regardless of age.
     */
    @Scheduled(
            fixedDelayString = "${socp.detect.state.cleanup-interval-ms:600000}",
            initialDelayString = "${socp.detect.state.cleanup-initial-delay-ms:600000}")
    public void cleanupExpiredTerminalEvents() {
        try {
            long deleted = repository.deleteTerminalBefore(
                    Set.of(DetectionEventStatus.COMPLETED.name(),
                            DetectionEventStatus.DEAD_LETTERED.name()),
                    Instant.now().minus(retention));
            if (deleted > 0) log.info("Expired Detection journal rows removed count={}", deleted);
        } catch (Exception failure) {
            log.warn("Detection journal retention cleanup deferred: {}", failure.getMessage());
        }
    }

    private List<SecurityEvent> readPages(PageReader reader, boolean sortByTimestamp) {
        List<DetectionEventEntity> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<DetectionEventEntity> batch = reader.read(page, replayPageSize);
            rows.addAll(batch);
            if (batch.size() < replayPageSize) break;
        }
        List<SecurityEvent> out = fromRows(rows);
        if (sortByTimestamp) out.sort(Comparator.comparing(SecurityEvent::timestamp));
        return out;
    }

    private void replayPages(PageReader reader, Consumer<List<SecurityEvent>> batchConsumer) {
        for (int page = 0; ; page++) {
            List<DetectionEventEntity> rows = reader.read(page, replayPageSize);
            List<SecurityEvent> events = fromRows(rows);
            if (!events.isEmpty()) batchConsumer.accept(events);
            if (rows.size() < replayPageSize) break;
        }
    }

    private List<SecurityEvent> fromRows(List<DetectionEventEntity> rows) {
        List<SecurityEvent> out = new ArrayList<>();
        for (DetectionEventEntity row : rows) {
            try {
                Map<String, String> fields = com.socp.rule.util.Json.mapper()
                        .readValue(row.getFieldsJson(), FIELDS);
                Severity severity;
                try {
                    severity = Severity.valueOf(row.getSeverity().toUpperCase());
                } catch (Exception ignored) {
                    severity = Severity.INFO;
                }
                out.add(new SecurityEvent(row.getEventId(), row.getOccurredAt(), row.getSource(),
                        row.getHost(), row.getRaw(), fields, severity));
            } catch (Exception ex) {
                log.warn("Unable to restore detection event eventId={}: {}",
                        row.getEventId(), ex.getMessage());
            }
        }
        return out;
    }

    private PendingDetectionEvent pendingRow(DetectionEventEntity row) {
        List<SecurityEvent> events = fromRows(List.of(row));
        if (events.isEmpty()) return null;
        return new PendingDetectionEvent(events.get(0), row.getKafkaPartition(), row.getKafkaOffset());
    }

    private static DetectionEventClaim claimOf(DetectionEventEntity row) {
        if (DetectionEventStatus.PENDING.name().equals(row.getStatus())) {
            return DetectionEventClaim.PENDING;
        }
        if (DetectionEventStatus.DEAD_LETTERED.name().equals(row.getStatus())) {
            return DetectionEventClaim.DEAD_LETTERED;
        }
        return DetectionEventClaim.COMPLETED;
    }

    private Instant cutoff(Duration window) {
        Duration requested = window == null || window.isNegative() || window.isZero()
                ? retention : window;
        return Instant.now().minus(requested.compareTo(retention) > 0 ? retention : requested);
    }

    private static String safe(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String truncate(String value) {
        return safe(value, "unknown", 1024);
    }

    private static Duration parseDuration(String value) {
        if (value == null || value.isBlank()) return Duration.ofHours(24);
        String s = value.trim().toLowerCase();
        try {
            if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException ex) {
            return Duration.ofHours(24);
        }
    }

    @FunctionalInterface
    private interface PageReader {
        List<DetectionEventEntity> read(int page, int size);
    }
}
