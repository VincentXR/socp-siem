package com.socp.detect.web.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.detect.web.metrics.DetectionPerformanceMetrics;
import com.socp.detect.web.service.DetectEngineService;
import com.socp.detect.web.persistence.store.DetectionEventClaim;
import com.socp.detect.web.persistence.store.DetectionStateOwnership;
import com.socp.detect.web.persistence.store.DetectionStateStore;
import com.socp.rule.partition.DetectionRoutingKey;
import com.socp.rule.partition.DetectionDelivery;
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
    private final long completionTimeoutMillis;

    DetectionRecordProcessor(DetectEngineService engine, DetectionStateStore stateStore,
                             DetectionPerformanceMetrics performanceMetrics) {
        this(engine, stateStore, performanceMetrics, TimeUnit.MINUTES.toMillis(10));
    }

    DetectionRecordProcessor(DetectEngineService engine, DetectionStateStore stateStore,
                             DetectionPerformanceMetrics performanceMetrics,
                             long completionTimeoutMillis) {
        this.engine = engine;
        this.stateStore = stateStore;
        this.performanceMetrics = performanceMetrics;
        this.completionTimeoutMillis = Math.max(1L, completionTimeoutMillis);
    }

    private boolean routedInput;

    void setRoutedInput(boolean routedInput) {
        this.routedInput = routedInput;
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
        try {
            processNormalized(topic, partition, offset, record.routingKey(), record.event());
        } catch (MalformedDetectionRecordException | RetryableDetectionFailure
                 | TerminalDetectionFailure typed) {
            throw typed;
        } catch (RuntimeException failure) {
            // A successfully parsed record is not poison merely because an
            // execution path threw an unknown RuntimeException. Unknown failures
            // stay retryable and visible until an operator or a typed boundary
            // can classify them more narrowly.
            throw retryable(record.event(), FailureStage.EVALUATION, failure);
        }
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

            DetectionEventClaim claim;
            try {
                claim = topic == null || topic.isBlank()
                        ? stateStore.claim(normalized, partition, offset, routingKey)
                        : stateStore.claim(normalized, topic, partition, offset, routingKey);
            } catch (RuntimeException failure) {
                throw retryable(normalized, FailureStage.CLAIM, failure);
            }
            if (performanceMetrics != null) performanceMetrics.journalCommitted(normalized);
            if (claim == DetectionEventClaim.COMPLETED || claim == DetectionEventClaim.DEAD_LETTERED) {
                if (performanceMetrics != null) {
                    performanceMetrics.terminalWithoutEvaluation(
                            normalized, claim.name().toLowerCase(Locale.ROOT));
                }
                return;
            }

            CompletableFuture<Void> completion;
            try {
                // Keep the lightweight/unit ingress path on the legacy overload;
                // only real Kafka records carry an ownership position that needs
                // to participate in the checkpoint vector.
                if (partition == null || offset == null) {
                    completion = engine.ingestFromKafkaAndAwait(normalized);
                } else if (topic == null || topic.isBlank()) {
                    completion = engine.ingestFromKafkaAndAwait(normalized, partition, offset);
                } else {
                    completion = engine.ingestFromKafkaAndAwait(normalized, topic, partition, offset);
                }
            } catch (RuntimeException failure) {
                throw retryable(normalized, FailureStage.EVALUATION, failure);
            }
            if (completion == null) {
                throw retryable(normalized, FailureStage.EVALUATION,
                        new IllegalStateException("detection completion signal is null"));
            }

            awaitInitialCompletion(normalized, partition, completion);
            finalizeCompletedEvaluation(normalized, partition);
        }
    }

    /**
     * The ten-minute timeout is an observability/recovery boundary, not permission
     * to start a second evaluation while the first future may still be running.
     * The consumer keeps this exact future and resumes waiting on it.
     */
    private void awaitInitialCompletion(SecurityEvent normalized, Integer partition,
                                        CompletableFuture<Void> completion) {
        try {
            completion.get(completionTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw retryable(normalized, FailureStage.ASYNC_EXECUTION, interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new InFlightDetectionTimeout(normalized, partition, completion, timeout);
        } catch (ExecutionException failed) {
            Throwable cause = unwrapAsync(failed);
            throw retryable(normalized, FailureStage.ASYNC_EXECUTION, cause);
        }
    }

    /**
     * Continue waiting on the original timed-out evaluation. A repeated timeout
     * rethrows the same in-flight token; no rule evaluation, sink write or state
     * advance is started concurrently.
     */
    void resumeTimedOut(InFlightDetectionTimeout timedOut, long waitMillis) {
        if (timedOut == null) throw new IllegalArgumentException("timedOut is required");
        try {
            timedOut.completion().get(Math.max(1L, waitMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw retryable(timedOut.event(), FailureStage.ASYNC_EXECUTION, interrupted);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw timedOut;
        } catch (ExecutionException failed) {
            throw retryable(timedOut.event(), FailureStage.ASYNC_EXECUTION, unwrapAsync(failed));
        }
        finalizeCompletedEvaluation(timedOut.event(), timedOut.partition());
    }

    /** Retry only the terminal journal transition; do not execute the rules again. */
    void resumeFinalization(FinalizationPendingFailure pending) {
        if (pending == null) throw new IllegalArgumentException("pending is required");
        finalizeCompletedEvaluation(pending.event(), pending.partition());
    }

    private void finalizeCompletedEvaluation(SecurityEvent normalized, Integer partition) {
        try {
            // Durable sink completion is necessary but not sufficient: the owner
            // that observed it must still hold the current fencing epoch before
            // the journal can advance to COMPLETED.
            if (partition != null) engine.assertCurrentOwner(normalized, partition);
            stateStore.markCompleted(normalized);
        } catch (RuntimeException failure) {
            FailureCategory category = classifyFailure(failure);
            if (category == FailureCategory.OWNERSHIP_LOST) {
                throw retryable(normalized, FailureStage.MARK_COMPLETED, failure);
            }
            throw new FinalizationPendingFailure(normalized, partition,
                    FailureStage.MARK_COMPLETED, category, failure);
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
                    null, raw, malformed);
        }
        if (payload == null || !payload.isObject()) {
            String terminalId = partition == null || offset == null
                    ? null : normalizeEventId(null, partition, offset);
            throw new MalformedDetectionRecordException(terminalId, null, raw,
                    new IllegalArgumentException("event payload must be an object"));
        }

        String suppliedEventId = text(payload, "eventId", null);
        String tenantHint = tenantHint(payload);
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
            if (!routedInput) DetectionDelivery.quarantineInputMetadata(fields);
            SecurityEvent event = new SecurityEvent(normalizeEventId(eventId, partition, offset),
                    parseTimestamp(payload, routedInput),
                    text(payload, "source", "unknown"), text(payload, "host", "unknown"),
                    message, fields, parseSeverity(payload));
            if (routedInput) DetectionDelivery.validate(event);
            return new NormalizedDetectionRecord(DetectionRoutingKey.forEvent(event), event);
        } catch (IllegalArgumentException malformed) {
            String terminalId = partition == null || offset == null
                    ? eventId : normalizeEventId(null, partition, offset);
            throw new MalformedDetectionRecordException(terminalId, tenantHint, raw, malformed);
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

    private static String tenantHint(JsonNode payload) {
        if (payload == null || !payload.isObject()) return null;
        String tenant = text(payload, "tenantId", text(payload, "tenant_id", null));
        JsonNode fields = payload.get("fields");
        if ((tenant == null || tenant.isBlank()) && fields != null && fields.isObject()) {
            tenant = text(fields, "tenant_id", text(fields, "tenantId", null));
        }
        return tenant == null || tenant.isBlank()
                || !com.socp.platform.tenant.context.TenantContext.isValid(tenant)
                ? null : tenant;
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

    private static Instant parseTimestamp(JsonNode payload, boolean routed) {
        String value = text(payload, "timestamp", null);
        if (value == null || value.isBlank()) {
            if (routed) throw new IllegalArgumentException("routed detection timestamp is required");
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (Exception failure) {
            if (routed) {
                throw new IllegalArgumentException("routed detection timestamp must be ISO-8601", failure);
            }
            return Instant.now();
        }
    }

    record NormalizedDetectionRecord(String routingKey, SecurityEvent event) {
    }

    enum FailureStage {
        CLAIM("claim"),
        EVALUATION("evaluation"),
        ASYNC_EXECUTION("async_execution"),
        MARK_COMPLETED("mark_completed");

        private final String metricTag;

        FailureStage(String metricTag) {
            this.metricTag = metricTag;
        }

        String metricTag() {
            return metricTag;
        }
    }

    enum FailureCategory {
        DEPENDENCY("dependency"),
        RECOVERY("recovery"),
        TIMEOUT("timeout"),
        BACKPRESSURE("backpressure"),
        OWNERSHIP_LOST("ownership_lost"),
        INTERRUPTED("interrupted"),
        UNKNOWN("unknown");

        private final String metricTag;

        FailureCategory(String metricTag) {
            this.metricTag = metricTag;
        }

        String metricTag() {
            return metricTag;
        }
    }

    /**
     * Classify from explicit exception types across the full cause chain.
     * IllegalArgumentException is intentionally not a generic poison marker:
     * after parsing, an unknown execution exception remains retryable.
     */
    static FailureCategory classifyFailure(Throwable failure) {
        for (Throwable current = failure; current != null;
             current = current.getCause() == current ? null : current.getCause()) {
            if (current instanceof DetectionStateOwnership.StaleStateOwnerException) {
                return FailureCategory.OWNERSHIP_LOST;
            }
            if (current instanceof TenantAdmission.RejectedException
                    || current instanceof java.util.concurrent.RejectedExecutionException) {
                return FailureCategory.BACKPRESSURE;
            }
            if (current instanceof DetectEngineService.RuntimeUnavailableException) {
                return FailureCategory.RECOVERY;
            }
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) {
                return FailureCategory.TIMEOUT;
            }
            if (current instanceof InterruptedException
                    || current instanceof java.util.concurrent.CancellationException) {
                return FailureCategory.INTERRUPTED;
            }
            if (current instanceof org.springframework.dao.DataAccessException
                    || current instanceof org.springframework.transaction.TransactionException
                    || current instanceof jakarta.persistence.PersistenceException
                    || current instanceof java.sql.SQLException
                    || current instanceof java.net.ConnectException
                    || current instanceof org.apache.kafka.common.KafkaException) {
                return FailureCategory.DEPENDENCY;
            }
        }
        return FailureCategory.UNKNOWN;
    }

    private static Throwable unwrapAsync(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof ExecutionException
                || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static RetryableDetectionFailure retryable(SecurityEvent event,
                                                       FailureStage stage,
                                                       Throwable cause) {
        String eventId = event == null ? null : event.id();
        String tenantId = event == null ? null : event.requireTenantId();
        FailureCategory category = classifyFailure(cause);
        String detail = cause == null ? "unknown failure"
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new RetryableDetectionFailure(eventId, tenantId, stage, category,
                "retryable detection failure stage=" + stage.metricTag()
                        + " category=" + category.metricTag() + " cause=" + detail,
                cause);
    }

    static class RetryableDetectionFailure extends RuntimeException {
        private final String eventId;
        private final String tenantId;
        private final FailureStage stage;
        private final FailureCategory category;

        RetryableDetectionFailure(String eventId, String tenantId, FailureStage stage,
                                  FailureCategory category, String message, Throwable cause) {
            super(message, cause);
            this.eventId = eventId;
            this.tenantId = tenantId;
            this.stage = stage;
            this.category = category;
        }

        String eventId() {
            return eventId;
        }

        String tenantId() {
            return tenantId;
        }

        FailureStage stage() {
            return stage;
        }

        FailureCategory category() {
            return category;
        }
    }

    static final class InFlightDetectionTimeout extends RetryableDetectionFailure {
        private final SecurityEvent event;
        private final Integer partition;
        private final CompletableFuture<Void> completion;

        InFlightDetectionTimeout(SecurityEvent event, Integer partition,
                                 CompletableFuture<Void> completion, Throwable cause) {
            super(event == null ? null : event.id(),
                    event == null ? null : event.requireTenantId(),
                    FailureStage.ASYNC_EXECUTION, FailureCategory.TIMEOUT,
                    "detection evaluation timed out while the original async task is still in flight",
                    cause);
            this.event = event;
            this.partition = partition;
            this.completion = completion;
        }

        SecurityEvent event() {
            return event;
        }

        Integer partition() {
            return partition;
        }

        CompletableFuture<Void> completion() {
            return completion;
        }
    }

    static final class FinalizationPendingFailure extends RetryableDetectionFailure {
        private final SecurityEvent event;
        private final Integer partition;

        FinalizationPendingFailure(SecurityEvent event, Integer partition,
                                   FailureStage stage, FailureCategory category, Throwable cause) {
            super(event == null ? null : event.id(),
                    event == null ? null : event.requireTenantId(),
                    stage, category,
                    "detection evaluation is durable but journal finalization is pending",
                    cause);
            this.event = event;
            this.partition = partition;
        }

        SecurityEvent event() {
            return event;
        }

        Integer partition() {
            return partition;
        }
    }

    /**
     * Source-compatible alias for older focused tests/integrations. New code uses
     * the richer retryable failure with explicit stage/category.
     */
    @Deprecated
    static final class DetectionUnavailableException extends RetryableDetectionFailure {
        DetectionUnavailableException(String tenantId, Throwable cause) {
            super(null, tenantId, FailureStage.EVALUATION, classifyFailure(cause),
                    "detection is temporarily unavailable: " + cause.getMessage(), cause);
        }

        String tenantId() {
            return super.tenantId();
        }
    }

    /**
     * A deterministic processing failure for one already-parsed event. It carries
     * the journal identity - the normalized event id plus its tenant - because the
     * Kafka routing key is a routing identity and the tenant scope is closed by
     * the time the dead-letter hand-off runs.
     */
    static final class TerminalDetectionFailure extends RuntimeException {
        private final String eventId;
        private final String tenantId;

        TerminalDetectionFailure(String eventId, String tenantId, String message, Throwable cause) {
            super(message, cause);
            this.eventId = eventId;
            this.tenantId = tenantId;
        }

        String eventId() {
            return eventId;
        }

        String tenantId() {
            return tenantId;
        }
    }

    static final class MalformedDetectionRecordException extends RuntimeException {
        private final String eventId;
        private final String tenantId;
        private final String raw;

        MalformedDetectionRecordException(String eventId, String tenantId,
                                          String raw, Throwable cause) {
            super("terminal record: " + cause.getMessage(), cause);
            this.eventId = eventId;
            this.tenantId = tenantId;
            this.raw = raw;
        }

        String eventId() {
            return eventId;
        }

        String tenantId() {
            return tenantId;
        }

        String raw() {
            return raw;
        }
    }
}
