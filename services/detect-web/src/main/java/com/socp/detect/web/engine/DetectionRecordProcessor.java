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
        process(null, partition, offset, key, raw);
    }

    void process(String topic, Integer partition, Long offset, String key, String raw) {
        NormalizedDetectionRecord record = parse(partition, offset, key, raw);
        if (key != null && !key.equals(record.routingKey())) {
            log.warn(
                    "Kafka routing key mismatch eventId={} received={} expected={}; using expected ownership",
                    record.event().id(), key, record.routingKey());
        }
        processNormalized(topic, partition, offset, record.routingKey(), record.event());
    }

    void processNormalized(Integer partition, Long offset, String routingKey, SecurityEvent normalized) {
        processNormalized(null, partition, offset, routingKey, normalized);
    }

    void processNormalized(String topic, Integer partition, Long offset,
                           String routingKey, SecurityEvent normalized) {
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

            // Keep the lightweight/unit ingress path on the legacy overload;
            // only real Kafka records carry an ownership position that needs
            // to participate in the checkpoint vector.
            CompletableFuture<Void> completion;
            if (partition == null || offset == null) {
                completion = engine.ingestFromKafkaAndAwait(normalized);
            } else if (topic == null || topic.isBlank()) {
                completion = engine.ingestFromKafkaAndAwait(normalized, partition, offset);
            } else {
                completion = engine.ingestFromKafkaAndAwait(normalized, topic, partition, offset);
            }
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
            // Mark the journal terminal only after the engine's durable sink
            // and owner-fenced position callback have completed. This also
            // covers zero-alert events and keeps completion independent of a
            // particular AlertForwarder implementation.
            stateStore.markCompleted(normalized);
        }
    }

    NormalizedDetectionRecord parse(String key, String raw) {
        return parse(null, null, key, raw);
    }

    NormalizedDetectionRecord parse(Integer partition, Long offset, String key, String raw) {
        JsonNode payload;
        try {
            payload = MAPPER.readTree(raw);
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw new MalformedDetectionRecordException(
                    partition == null || offset == null ? null : normalizeEventId(null, partition, offset),
                    raw, malformed);
        }
        if (payload == null || !payload.isObject()) {
            String terminalId = partition == null || offset == null
                    ? null : normalizeEventId(null, partition, offset);
            throw new MalformedDetectionRecordException(terminalId, raw,
                    new IllegalArgumentException("event payload must be an object"));
        }

        String suppliedEventId = text(payload, "eventId", null);
        // The Kafka key is a routing identity, not an event identity. When a
        // producer omits eventId, the immutable record position prevents a
        // redelivery from turning into a fresh UUID and bypassing the journal
        // claim. HTTP/unit callers retain the legacy key fallback.
        String eventId = suppliedEventId == null && (partition == null || offset == null)
                ? key : suppliedEventId;
        try {
            JsonNode rawFields = payload.get("fields");
            if (rawFields != null && !rawFields.isNull() && !rawFields.isObject()) {
                throw new IllegalArgumentException("fields must be an object");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            if (rawFields != null && rawFields.isObject()) {
                rawFields.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue().asText()));
            }
            // The ingest contract keeps compatibility fields and ECS fields in
            // separate namespaces for OpenSearch mapping stability. Detection
            // rules, however, evaluate one logical field map (for example
            // event.category or source.ip). Bridge ECS keys without allowing
            // them to overwrite an explicitly supplied compatibility field.
            JsonNode rawEcs = payload.get("ecs");
            if (rawEcs != null && !rawEcs.isNull()) {
                if (!rawEcs.isObject()) {
                    throw new IllegalArgumentException("ecs must be an object");
                }
                rawEcs.fields().forEachRemaining(entry ->
                        fields.putIfAbsent(entry.getKey(), entry.getValue().asText()));
            }
            String tenant = text(payload, "tenantId", text(payload, "tenant_id", fields.get("tenant_id")));
            if (tenant == null || tenant.isBlank() || !com.socp.platform.tenant.context.TenantContext.isValid(tenant)) {
                throw new IllegalArgumentException("event tenant is required and must be valid");
            }
            fields.put("tenant_id", tenant);
            String message = text(payload, "msg", text(payload, "message", ""));
            if (payload.has("msg") && !fields.containsKey("msg")) fields.put("msg", message);
            SecurityEvent event = new SecurityEvent(normalizeEventId(eventId, partition, offset), parseTimestamp(payload),
                    text(payload, "source", "unknown"), text(payload, "host", "unknown"),
                    message, fields, parseSeverity(payload));
            return new NormalizedDetectionRecord(DetectionRoutingKey.forEvent(event), event);
        } catch (IllegalArgumentException malformed) {
            String terminalId = partition == null || offset == null
                    ? eventId : normalizeEventId(null, partition, offset);
            throw new MalformedDetectionRecordException(terminalId, raw, malformed);
        }
    }

    private static String normalizeEventId(String eventId, Integer partition, Long offset) {
        if (eventId == null || eventId.isBlank() || "null".equalsIgnoreCase(eventId)) {
            if (partition != null && offset != null) {
                return "kafka-offset:" + partition + ":" + offset;
            }
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
