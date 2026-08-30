package com.socp.detect.web.persistence.store;


import com.socp.detect.web.persistence.repository.DetectionEventRepository;
import com.socp.detect.web.persistence.entity.DetectionEventEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.platform.tenant.persistence.TenantSystemJob;
import com.socp.platform.tenant.context.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Duration completedRetention;
    private final Duration deadLetterRetention;
    private final int replayPageSize;

    /** Spring constructor keeps replay and terminal-retention policies explicit. */
    @Autowired
    public DetectionEventJournal(DetectionEventRepository repository,
                                 @Value("${socp.detect.state.retention:24h}") String retention,
                                 @Value("${socp.detect.state.replay-page-size:1000}") int replayPageSize,
                                 @Value("${socp.detect.state.completed-retention:7d}") String completedRetention,
                                 @Value("${socp.detect.state.dead-letter-retention:90d}") String deadLetterRetention) {
        this.repository = repository;
        this.retention = parsePositiveDuration(retention, Duration.ofHours(24));
        Duration configuredCompletedRetention = parsePositiveDuration(
                completedRetention, Duration.ofDays(7));
        // Completed rows are the source of truth for rebuilding the configured
        // replay window. Never let maintenance delete that source earlier than
        // the window it promises to recover.
        this.completedRetention = configuredCompletedRetention.compareTo(this.retention) < 0
                ? this.retention : configuredCompletedRetention;
        this.deadLetterRetention = parsePositiveDuration(deadLetterRetention, Duration.ofDays(90));
        this.replayPageSize = Math.max(100, Math.min(10_000, replayPageSize));
    }

    /** Compatibility constructor used by focused unit tests and local callers. */
    public DetectionEventJournal(DetectionEventRepository repository, String retention,
                                 int replayPageSize) {
        this(repository, retention, replayPageSize, "7d", "90d");
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
        String tenant = event.requireTenantId();
        var existing = repository.findByTenantIdAndSourceEventId(tenant, event.id());
        if (existing.isPresent()) return claimOf(existing.get());

        try {
            String fields = com.socp.rule.util.Json.mapper().writeValueAsString(
                    event.fields() == null ? Map.of() : event.fields());
            repository.saveAndFlush(new DetectionEventEntity(
                    tenant, event.id(), safe(event.source(), "unknown", 64),
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
        markCompleted(com.socp.platform.tenant.context.TenantContext.require(), eventId);
    }

    @Override
    @Transactional
    public void markCompleted(String tenantId, String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findByTenantIdAndSourceEventId(tenantId, eventId).ifPresent(row -> {
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
        markDeadLettered(com.socp.platform.tenant.context.TenantContext.require(), eventId, reason);
    }

    @Override
    @Transactional
    public void markDeadLettered(String tenantId, String eventId, String reason) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findByTenantIdAndSourceEventId(tenantId, eventId).ifPresent(row -> {
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
        String tenant = com.socp.platform.tenant.context.TenantContext.get();
        if (tenant == null || tenant.isBlank()) {
            log.warn("Skipping terminal Detection journal row without a tenant eventId={}", eventId);
            return;
        }
        var existing = repository.findByTenantIdAndSourceEventId(tenant, eventId);
        if (existing.isPresent()) {
            markDeadLettered(tenant, eventId, reason);
            return;
        }
        DetectionEventEntity row = new DetectionEventEntity(
                tenant, eventId, "unknown", "unknown", safe(raw, "", 8192), "{}", Severity.INFO.name(),
                Instant.now(), partition, offset, null);
        row.setStatus(DetectionEventStatus.DEAD_LETTERED.name());
        row.setDeadLetteredAt(Instant.now());
        row.setStatusReason(truncate(reason));
        repository.saveAndFlush(row);
    }

    @Override
    @Transactional
    public void remove(String eventId) {
        remove(com.socp.platform.tenant.context.TenantContext.require(), eventId);
    }

    @Override
    @Transactional
    public void remove(String tenantId, String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findByTenantIdAndSourceEventId(tenantId, eventId).ifPresent(repository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> recent(Duration window) {
        if (TenantContext.isSystemScope()) {
            return readPages((page, size) -> repository
                    .findByStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                            DetectionEventStatus.COMPLETED.name(), cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), true);
        }
        String tenant = TenantContext.require();
        return readPages((page, size) -> repository
                .findByTenantIdAndStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                        tenant, DetectionEventStatus.COMPLETED.name(), cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> recentForPartitions(Set<Integer> partitions, Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        if (TenantContext.isSystemScope()) {
            return readPages((page, size) -> repository
                    .findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                            DetectionEventStatus.COMPLETED.name(), partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), false);
        }
        String tenant = TenantContext.require();
        return readPages((page, size) -> repository
                .findByTenantIdAndStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                        tenant, DetectionEventStatus.COMPLETED.name(), partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), false);
    }

    @Override
    public void replayRecent(Duration window, Consumer<List<SecurityEvent>> batchConsumer) {
        if (TenantContext.isSystemScope()) {
            replayPages((page, size) -> repository
                    .findByStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                            DetectionEventStatus.COMPLETED.name(), cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
            return;
        }
        String tenant = TenantContext.require();
        replayPages((page, size) -> repository
                .findByTenantIdAndStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                        tenant, DetectionEventStatus.COMPLETED.name(), cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    public void replayRecentForPartitions(Set<Integer> partitions, Duration window,
                                          Consumer<List<SecurityEvent>> batchConsumer) {
        if (partitions == null || partitions.isEmpty()) return;
        if (TenantContext.isSystemScope()) {
            replayPages((page, size) -> repository
                    .findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                            DetectionEventStatus.COMPLETED.name(), partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
            return;
        }
        String tenant = TenantContext.require();
        replayPages((page, size) -> repository
                .findByTenantIdAndStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                        tenant, DetectionEventStatus.COMPLETED.name(), partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    public void replayRecentForTenant(String tenantId, Duration window,
                                      Consumer<List<SecurityEvent>> batchConsumer) {
        replayPages((page, size) -> repository
                .findByTenantIdAndStatusAndOccurredAtAfterOrderByOccurredAtAscSourceEventIdAsc(
                        tenantId, DetectionEventStatus.COMPLETED.name(), cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), batchConsumer);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> pendingForPartitions(Set<Integer> partitions, Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        if (TenantContext.isSystemScope()) {
            return readPages((page, size) -> repository
                    .findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                            DetectionEventStatus.PENDING.name(), partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, size)), false);
        }
        String tenant = TenantContext.require();
        return readPages((page, size) -> repository
                .findByTenantIdAndStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                        tenant, DetectionEventStatus.PENDING.name(), partitions, cutoff(window),
                        org.springframework.data.domain.PageRequest.of(page, size)), false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingDetectionEvent> pendingRecordsForPartitions(Set<Integer> partitions,
                                                                    Duration window) {
        if (partitions == null || partitions.isEmpty()) return List.of();
        String tenant = TenantContext.isSystemScope() ? null : TenantContext.require();
        List<DetectionEventEntity> rows = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<DetectionEventEntity> batch = tenant == null
                    ? repository.findByStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                            DetectionEventStatus.PENDING.name(), partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, replayPageSize))
                    : repository.findByTenantIdAndStatusAndKafkaPartitionInAndOccurredAtAfterOrderByKafkaPosition(
                            tenant, DetectionEventStatus.PENDING.name(), partitions, cutoff(window),
                            org.springframework.data.domain.PageRequest.of(page, replayPageSize));
            rows.addAll(batch);
            if (batch.size() < replayPageSize) break;
        }
        return rows.stream().map(this::pendingRow).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount() {
        return TenantContext.isSystemScope()
                ? repository.countByStatus(DetectionEventStatus.PENDING.name())
                : repository.countByTenantIdAndStatus(TenantContext.require(), DetectionEventStatus.PENDING.name());
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingCount(String tenantId) {
        return repository.countByTenantIdAndStatus(tenantId, DetectionEventStatus.PENDING.name());
    }

    @Override
    public String recoveryWindow() {
        return retention.toString();
    }

    @Override
    public boolean supportsCheckpointReplay() {
        return true;
    }

    @Override
    public void replayCompletedAfter(String tenantId, Instant checkpoint,
                                     Set<Integer> partitions,
                                     Consumer<List<SecurityEvent>> batchConsumer) {
        if (tenantId == null || tenantId.isBlank() || checkpoint == null || batchConsumer == null) return;
        for (int page = 0; ; page++) {
            org.springframework.data.domain.Pageable request =
                    org.springframework.data.domain.PageRequest.of(page, replayPageSize);
            List<DetectionEventEntity> rows = partitions == null || partitions.isEmpty()
                    ? repository.findByTenantIdAndStatusAndCompletedAtAfterOrderByCompletedAt(
                            tenantId, DetectionEventStatus.COMPLETED.name(), checkpoint, request)
                    : repository.findByTenantIdAndStatusAndKafkaPartitionInAndCompletedAtAfterOrderByCompletedAt(
                            tenantId, DetectionEventStatus.COMPLETED.name(), partitions, checkpoint, request);
            List<SecurityEvent> events = fromRows(rows);
            if (!events.isEmpty()) batchConsumer.accept(events);
            if (rows.size() < replayPageSize) break;
        }
    }

    public Duration retention() {
        return retention;
    }

    /**
     * Keep retention maintenance out of the per-event claim transaction.
     * PENDING rows are deliberately excluded: they represent work whose Kafka
     * offset cannot advance and must remain recoverable regardless of age.
     * COMPLETED and DEAD_LETTERED rows have independent retention clocks. A
     * terminal failure is retained longer because it is the durable evidence
     * needed to explain why an input was not evaluated.
     */
    @Scheduled(
            fixedDelayString = "${socp.detect.state.cleanup-interval-ms:600000}",
            initialDelayString = "${socp.detect.state.cleanup-initial-delay-ms:600000}")
    @TenantSystemJob
    public void cleanupExpiredTerminalEvents() {
        try {
            Instant now = Instant.now();
            long completed = repository.deleteCompletedBefore(
                    DetectionEventStatus.COMPLETED.name(), now.minus(completedRetention));
            long deadLettered = repository.deleteDeadLetteredBefore(
                    DetectionEventStatus.DEAD_LETTERED.name(), now.minus(deadLetterRetention));
            if (completed > 0 || deadLettered > 0) {
                log.info("Expired Detection journal rows removed completed={} deadLettered={}",
                        completed, deadLettered);
            }
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
                fields.putIfAbsent("tenant_id", row.getTenantId());
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
        return parseDuration(value, Duration.ofHours(24));
    }

    private static Duration parseDuration(String value, Duration fallback) {
        if (value == null || value.isBlank()) return fallback;
        String s = value.trim().toLowerCase();
        try {
            if (s.endsWith("d")) return Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException | ArithmeticException ex) {
            return fallback;
        }
    }

    private static Duration parsePositiveDuration(String value, Duration fallback) {
        Duration parsed = parseDuration(value, fallback);
        return parsed.isNegative() || parsed.isZero() ? fallback : parsed;
    }

    @FunctionalInterface
    private interface PageReader {
        List<DetectionEventEntity> read(int page, int size);
    }
}
