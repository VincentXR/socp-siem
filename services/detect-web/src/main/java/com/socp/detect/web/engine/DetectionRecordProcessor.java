package com.socp.detect.web.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Converts a Kafka payload into a canonical event and owns the durable
 * Detection hand-off. Keeping this boundary separate from Kafka polling makes
 * malformed-payload handling and record processing independently testable.
 */
final class DetectionRecordProcessor {

    private static final Logger log = LoggerFactory.getLogger(DetectionRecordProcessor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final DetectEngineService engine;
    private final DetectionStateStore stateStore;
    private final DetectionPerformanceMetrics performanceMetrics;

    DetectionRecordProcessor(DetectEngineService engine, DetectionStateStore stateStore,
                             DetectionPerformanceMetrics performanceMetrics) {
        this.engine = engine;
        this.stateStore = stateStore;
        this.performanceMetrics = performanceMetrics;
    }

    void process(Integer partition, Long offset, String key, String raw) {
        NormalizedDetectionRecord record = parse(key, raw);
        if (key != null && !key.equals(record.routingKey())) {
            log.warn(
                    "Kafka routing key mismatch eventId={} received={} expected={}; using expected ownership",
                    record.event().id(), key, record.routingKey());
        }
        processNormalized(partition, offset, record.routingKey(), record.event());
    }

    void processNormalized(Integer partition, Long offset, String routingKey, SecurityEvent normalized) {
        if (normalized == null) throw new IllegalArgumentException("normalized event is required");
        String tenant = normalized.requireTenantId();
        try (com.socp.platform.tenant.context.TenantContext.Scope ignored =
                     com.socp.platform.tenant.context.TenantContext.open(tenant)) {
            if (performanceMetrics != null) performanceMetrics.kafkaReceived(normalized);
            DetectionEventClaim claim = stateStore.claim(normalized, partition, offset, routingKey);
            if (performanceMetrics != null) performanceMetrics.journalCommitted(normalized);
            if (claim == DetectionEventClaim.COMPLETED || claim == DetectionEventClaim.DEAD_LETTERED) {
                if (performanceMetrics != null) {
                    performanceMetrics.terminalWithoutEvaluation(
                            normalized, claim.name().toLowerCase(Locale.ROOT));
                }
                return;
            }

            CompletableFuture<Void> completion = engine.ingestFromKafkaAndAwait(normalized);
            if (completion == null) throw new IllegalStateException("detection completion signal is null");
            try {
                completion.get(10, TimeUnit.MINUTES);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("detection processing interrupted", interrupted);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new IllegalStateException("detection processing timeout", timeout);
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                throw new IllegalStateException("durable detection result failed: " + cause.getMessage(), cause);
            }
            engine.snapshotAfterDurable(normalized, partition, offset);
        }
    }

    NormalizedDetectionRecord parse(String key, String raw) {
        JsonNode payload;
        try {
            payload = MAPPER.readTree(raw);
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw new MalformedDetectionRecordException(null, raw, malformed);
        }
        if (payload == null || !payload.isObject()) {
            throw new MalformedDetectionRecordException(null, raw,
                    new IllegalArgumentException("event payload must be an object"));
        }

        String eventId = text(payload, "eventId", key);
        try {
            JsonNode rawFields = payload.get("fields");
            if (rawFields != null && !rawFields.isNull() && !rawFields.isObject()) {
                throw new IllegalArgumentException("fields must be an object");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            if (rawFields != null && rawFields.isObject()) {
                rawFields.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));
            }
            String tenant = text(payload, "tenantId", text(payload, "tenant_id", fields.get("tenant_id")));
            if (tenant == null || tenant.isBlank() || !com.socp.platform.tenant.context.TenantContext.isValid(tenant)) {
                throw new IllegalArgumentException("event tenant is required and must be valid");
            }
            fields.put("tenant_id", tenant);
            String message = text(payload, "msg", text(payload, "message", ""));
            if (payload.has("msg") && !fields.containsKey("msg")) fields.put("msg", message);
            SecurityEvent event = new SecurityEvent(normalizeEventId(eventId), parseTimestamp(payload),
                    text(payload, "source", "unknown"), text(payload, "host", "unknown"),
                    message, fields, parseSeverity(payload));
            return new NormalizedDetectionRecord(DetectionRoutingKey.forEvent(event), event);
        } catch (IllegalArgumentException malformed) {
            throw new MalformedDetectionRecordException(eventId, raw, malformed);
        }
    }

    private static String normalizeEventId(String eventId) {
        if (eventId == null || eventId.isBlank() || "null".equalsIgnoreCase(eventId)) {
            return UUID.randomUUID().toString();
        }
        return eventId.trim();
    }

    private static String text(JsonNode payload, String field, String fallback) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static Severity parseSeverity(JsonNode payload) {
        try {
            return Severity.valueOf(text(payload, "severity", "INFO").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Severity.INFO;
        }
    }

    private static Instant parseTimestamp(JsonNode payload) {
        try {
            return Instant.parse(text(payload, "timestamp", Instant.now().toString()));
        } catch (Exception ignored) {
            return Instant.now();
        }
    }

    record NormalizedDetectionRecord(String routingKey, SecurityEvent event) {
    }

    static final class MalformedDetectionRecordException extends RuntimeException {
        private final String eventId;
        private final String raw;

        MalformedDetectionRecordException(String eventId, String raw, Throwable cause) {
            super("terminal record: " + cause.getMessage(), cause);
            this.eventId = eventId;
            this.raw = raw;
        }

        String eventId() {
            return eventId;
        }

        String raw() {
            return raw;
        }
    }
}
