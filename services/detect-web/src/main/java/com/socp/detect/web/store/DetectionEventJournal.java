package com.socp.detect.web.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PostgreSQL/H2-backed recovery journal for accepted detection events.
 *
 * <p>The journal is deliberately bounded by a configurable replay window. It
 * is not an archive; OpenSearch remains the investigation store. Its purpose
 * is to make threshold/correlation/baseline/rare state recoverable after a
 * process restart and to provide a durable event-id claim for at-least-once
 * Kafka delivery.</p>
 */
@Component
public class DetectionEventJournal implements DetectionStateStore {

    private static final Logger log = LoggerFactory.getLogger(DetectionEventJournal.class);
    private static final TypeReference<Map<String, String>> FIELDS = new TypeReference<>() {};

    private final DetectionEventRepository repository;
    private final Duration retention;
    private final AtomicLong writes = new AtomicLong();

    public DetectionEventJournal(DetectionEventRepository repository,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${socp.detect.state.retention:24h}") String retention) {
        this.repository = repository;
        this.retention = parseDuration(retention);
    }

    @Override
    @Transactional
    public boolean recordIfNew(SecurityEvent event) {
        if (event == null || event.id() == null || event.id().isBlank()) return false;
        if (repository.existsById(event.id())) return false;
        try {
            String fields = com.socp.rule.util.Json.mapper().writeValueAsString(
                    event.fields() == null ? Map.of() : event.fields());
            repository.saveAndFlush(new DetectionEventEntity(
                    event.id(), safe(event.source(), "unknown", 64),
                    safe(event.host(), "unknown", 255), safe(event.raw(), "", 8192),
                    fields, event.severity() == null ? Severity.INFO.name() : event.severity().name(),
                    event.timestamp() == null ? Instant.now() : event.timestamp()));
            // Prune lazily so the hot path does not need a scheduler or a second
            // durable queue. A timestamp index keeps this cheap in both H2 and PG.
            if (writes.incrementAndGet() % 500 == 0) {
                repository.deleteByOccurredAtBefore(Instant.now().minus(retention));
            }
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // Two Kafka consumers can race on the same event id; the unique PK
            // is the final arbiter, not the in-process cache.
            return false;
        } catch (Exception ex) {
            throw new IllegalStateException("无法写入检测状态日志: " + ex.getMessage(), ex);
        }
    }

    @Override
    @Transactional
    public void remove(String eventId) {
        if (eventId != null && !eventId.isBlank()) repository.deleteById(eventId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SecurityEvent> recent(Duration window) {
        Duration requested = window == null || window.isNegative() || window.isZero()
                ? retention : window;
        Instant cutoff = Instant.now().minus(requested.compareTo(retention) > 0 ? retention : requested);
        List<SecurityEvent> out = new ArrayList<>();
        for (DetectionEventEntity row : repository.findTop10000ByOccurredAtAfterOrderByOccurredAtAsc(cutoff)) {
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
                log.warn("忽略无法恢复的检测事件 eventId={}: {}", row.getEventId(), ex.getMessage());
            }
        }
        out.sort(Comparator.comparing(SecurityEvent::timestamp));
        return out;
    }

    public Duration retention() {
        return retention;
    }

    @Override
    public String recoveryWindow() {
        return retention.toString();
    }

    private static String safe(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() <= max ? value : value.substring(0, max);
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
}
