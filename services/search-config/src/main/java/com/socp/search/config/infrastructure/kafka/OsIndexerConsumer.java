package com.socp.search.config.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.search.config.config.KafkaProperties;
import com.socp.search.config.config.OpenSearchIndexerProperties;
import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.infrastructure.opensearch.BulkWriteResult;
import com.socp.search.config.infrastructure.opensearch.OsEventWriter;
import com.socp.search.config.schema.CanonicalEventSchema;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replayable Kafka-to-OpenSearch indexer.
 *
 * <p>Offsets advance independently per partition only after every valid event
 * in that partition's poll batch receives a successful OpenSearch bulk item
 * acknowledgement, or an invalid event receives a broker-acknowledged DLQ
 * record. Failed partitions seek to their first unresolved offset. OpenSearch
 * uses tenantId plus eventId as document _id, so replay is idempotent and
 * tenant-scoped.</p>
 */
@Component
public class OsIndexerConsumer {

    private static final Logger log = LoggerFactory.getLogger(OsIndexerConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final boolean indexerEnabled;
    private final long retryBackoffMs;

    private final OsEventWriter osWriter;
    private final KafkaProperties kafkaProperties;
    private final MeterRegistry meterRegistry;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> activeConsumer;
    private volatile KafkaProducer<String, String> dlqProducer;
    private Thread worker;

    public OsIndexerConsumer(OsEventWriter osWriter) {
        this(osWriter, new KafkaProperties(), new OpenSearchIndexerProperties(), null);
    }

    public OsIndexerConsumer(OsEventWriter osWriter, KafkaProperties kafkaProperties,
                             OpenSearchIndexerProperties properties) {
        this(osWriter, kafkaProperties, properties, null);
    }

    @Autowired
    public OsIndexerConsumer(OsEventWriter osWriter, KafkaProperties kafkaProperties,
                             OpenSearchIndexerProperties properties, MeterRegistry meterRegistry) {
        this.osWriter = osWriter;
        this.kafkaProperties = kafkaProperties;
        this.indexerEnabled = properties.isEnabled();
        this.retryBackoffMs = properties.getRetryBackoffMs();
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void start() {
        if (!kafkaProperties.isEnabled() || !indexerEnabled) return;
        running = true;
        worker = Thread.ofPlatform().name("os-indexer").daemon(true).start(this::runLoop);
        log.info("OpenSearch indexer started bootstrap={} topic={}",
                kafkaProperties.getBootstrap(), kafkaProperties.getTopic());
    }

    private void runLoop() {
        while (running) {
            if (!osWriter.ensureIndexTemplate()) {
                recordMetric("template_not_ready", 1);
                log.warn("OpenSearch indexer waiting for the production index template");
                backoffAfterFailure();
                continue;
            }
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties())) {
                activeConsumer = consumer;
                consumer.subscribe(List.of(kafkaProperties.getTopic()));
                consume(consumer);
            } catch (WakeupException wakeup) {
                if (running) log.warn("OpenSearch indexer consumer was unexpectedly woken");
            } catch (Exception failure) {
                if (running) {
                    log.warn("OpenSearch indexer consumer failed; restarting: {}", failure.getMessage());
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                activeConsumer = null;
            }
        }
    }

    private void consume(KafkaConsumer<String, String> consumer) {
        while (running) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            if (records.isEmpty()) continue;
            if (!processRecords(consumer, records)) backoffAfterFailure();
        }
    }

    /** Advances only successful partitions and rewinds failed partitions. */
    boolean processRecords(KafkaConsumer<String, String> consumer,
                           ConsumerRecords<String, String> records) {
        recordMetric("consume", records.count());
        Map<TopicPartition, OffsetAndMetadata> completed = new HashMap<>();
        long committedRecords = 0L;
        boolean allCompleted = true;
        for (TopicPartition partition : records.partitions()) {
            List<ConsumerRecord<String, String>> partitionRecords = records.records(partition);
            PartitionOutcome outcome = processPartitionOutcome(partitionRecords);
            long firstOffset = partitionRecords.getFirst().offset();
            if (outcome.nextOffset() > firstOffset) {
                completed.put(partition, new OffsetAndMetadata(outcome.nextOffset()));
                committedRecords += partitionRecords.stream()
                        .filter(record -> record.offset() < outcome.nextOffset()).count();
            }
            if (!outcome.completed()) {
                allCompleted = false;
                consumer.seek(partition, outcome.nextOffset());
            }
        }
        if (!completed.isEmpty()) {
            try {
                consumer.commitSync(completed);
                recordMetric("commit", committedRecords);
            } catch (RuntimeException commitFailure) {
                recordMetric("commit_failed", committedRecords);
                throw commitFailure;
            }
        }
        return allCompleted;
    }

    private void backoffAfterFailure() {
        long delay = Math.max(100L, Math.min(10_000L, retryBackoffMs));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /** Package-visible correctness seam used by focused tests. */
    boolean processPartition(List<ConsumerRecord<String, String>> records) {
        return processPartitionOutcome(records).completed();
    }

    private PartitionOutcome processPartitionOutcome(List<ConsumerRecord<String, String>> records) {
        String traceId = traceId(records.getFirst());
        if (traceId != null) org.slf4j.MDC.put("traceId", traceId);
        try {
            List<PreparedRecord> prepared = new ArrayList<>(records.size());
            List<SearchEvent> events = new ArrayList<>(records.size());
            for (ConsumerRecord<String, String> record : records) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> value = MAPPER.readValue(record.value(), Map.class);
                    CanonicalEventSchema.requireSupported(value);
                    SearchEvent event = toEvent(value, record.key());
                    prepared.add(new PreparedRecord(record, event, events.size(), null));
                    events.add(event);
                } catch (Exception invalid) {
                    prepared.add(new PreparedRecord(record, null, -1, invalidRecord(record, invalid)));
                }
            }
            BulkWriteResult result = events.isEmpty()
                    ? BulkWriteResult.empty() : osWriter.writeEventsAndAwait(events);
            recordBulkMetrics(result);
            Map<Integer, BulkWriteResult.Failure> retryable = failuresByIndex(result.retryableFailures());
            Map<Integer, BulkWriteResult.Failure> permanent = failuresByIndex(result.permanentFailures());
            long nextOffset = records.getFirst().offset();
            for (PreparedRecord item : prepared) {
                if (item.invalid() != null) {
                    recordMetric("drop", 1);
                    recordMetric(item.invalid().reasonCode(), 1);
                    if (!sendToDlqAndAwait(item.record(), item.invalid())) {
                        recordMetric("dlq_failed", 1);
                        return failedOutcome(records, nextOffset, item.record(), item.invalid().reasonCode(), result);
                    }
                    recordMetric("dlq", 1);
                } else if (retryable.containsKey(item.bulkIndex())) {
                    BulkWriteResult.Failure failure = retryable.get(item.bulkIndex());
                    return failedOutcome(records, nextOffset, item.record(), failure.reasonCode(), result);
                } else if (permanent.containsKey(item.bulkIndex())) {
                    BulkWriteResult.Failure failure = permanent.get(item.bulkIndex());
                    InvalidRecord invalid = new InvalidRecord(item.event().eventId(),
                            item.event().tenantId(), item.event().schemaVersion(),
                            failure.reasonCode(), failure.reason());
                    recordMetric("drop", 1);
                    recordMetric("permanent_failure", 1);
                    if (!sendToDlqAndAwait(item.record(), invalid)) {
                        recordMetric("dlq_failed", 1);
                        return failedOutcome(records, nextOffset, item.record(), "dlq_failed", result);
                    }
                    recordMetric("dlq", 1);
                } else if (!result.acknowledgedIds().contains(item.event().eventId())) {
                    return failedOutcome(records, nextOffset, item.record(), "bulk_ack_missing", result);
                }
                nextOffset = item.record().offset() + 1;
            }
            log.info("OpenSearch indexer partition={} batchSize={} firstOffset={} lastOffset={} bulkTookMs={} status=complete",
                    records.getFirst().partition(), records.size(), records.getFirst().offset(),
                    records.getLast().offset(), result.tookMs());
            return new PartitionOutcome(nextOffset, true);
        } finally {
            org.slf4j.MDC.remove("traceId");
        }
    }

    private PartitionOutcome failedOutcome(List<ConsumerRecord<String, String>> records, long nextOffset,
                                           ConsumerRecord<String, String> failed,
                                           String reasonCode, BulkWriteResult result) {
        log.warn("OpenSearch indexer partition={} batchSize={} firstOffset={} lastOffset={} retryOffset={} bulkTookMs={} reason={}",
                failed.partition(), records.size(), records.getFirst().offset(), records.getLast().offset(),
                failed.offset(), result.tookMs(), reasonCode);
        return new PartitionOutcome(nextOffset, false);
    }

    private void recordBulkMetrics(BulkWriteResult result) {
        recordMetric("write", result.acknowledgedIds().size());
        recordMetric("fail", result.retryableFailures().size());
        if (!result.acknowledgedIds().isEmpty()
                && (!result.retryableFailures().isEmpty() || !result.permanentFailures().isEmpty())) {
            recordMetric("bulk_partial_failure", 1);
        }
        if (result.retryableFailures().stream()
                .anyMatch(failure -> "template_not_ready".equals(failure.reasonCode()))) {
            recordMetric("template_not_ready", 1);
        }
    }

    private static Map<Integer, BulkWriteResult.Failure> failuresByIndex(
            List<BulkWriteResult.Failure> failures) {
        Map<Integer, BulkWriteResult.Failure> byIndex = new HashMap<>();
        failures.forEach(failure -> byIndex.put(failure.itemIndex(), failure));
        return byIndex;
    }

    private static String traceId(ConsumerRecord<String, String> record) {
        try {
            var header = record.headers().lastHeader("traceparent");
            if (header == null) return null;
            return com.socp.platform.obs.web.TraceIdFilter.parseTraceId(
                    new String(header.value(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static InvalidRecord invalidRecord(ConsumerRecord<String, String> record, Exception failure) {
        String reasonCode;
        if (failure instanceof CanonicalEventSchema.UnsupportedSchemaVersionException) {
            reasonCode = "schema_rejected";
        } else if (failure instanceof CanonicalEventSchema.SchemaValidationException) {
            reasonCode = "schema_invalid";
        } else {
            reasonCode = "invalid_payload";
        }
        String eventId = record.key();
        String tenant = null;
        String schemaVersion = "absent";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> value = MAPPER.readValue(record.value(), Map.class);
            if (value.get("eventId") != null) eventId = String.valueOf(value.get("eventId"));
            if (value.get("tenantId") != null) tenant = String.valueOf(value.get("tenantId"));
            if (value.get("schemaVersion") != null) schemaVersion = String.valueOf(value.get("schemaVersion"));
            if (tenant == null && value.get("fields") instanceof Map<?, ?> fields
                    && fields.get("tenant_id") != null) {
                tenant = String.valueOf(fields.get("tenant_id"));
            }
        } catch (Exception ignored) {
            // The original parse failure is the authoritative diagnostic.
        }
        return new InvalidRecord(eventId, tenant, schemaVersion, reasonCode,
                cleanReason(failure.getMessage(), failure.getClass().getSimpleName()));
    }

    /** Package-visible seam lets the DLQ acknowledgement contract be tested without a broker. */
    boolean sendToDlqAndAwait(ConsumerRecord<String, String> record, InvalidRecord failure) {
        try {
            String payload = dlqEnvelope(record, failure);
            String dlqKey = failure.eventId() == null || failure.eventId().isBlank()
                    ? record.topic() + "-" + record.partition() + "-" + record.offset()
                    : failure.eventId();
            KafkaClientSupport.sendAndAwait(dlq(), kafkaProperties.getTopic() + "-dlq",
                    dlqKey, payload, Duration.ofSeconds(30));
            return true;
        } catch (RuntimeException deliveryFailure) {
            log.warn("DLQ durable write failed partition={} offset={}: {}",
                    record.partition(), record.offset(), deliveryFailure.getMessage());
            return false;
        } catch (Exception serializationFailure) {
            log.warn("DLQ envelope serialization failed partition={} offset={}: {}",
                    record.partition(), record.offset(), serializationFailure.getMessage());
            return false;
        }
    }

    static String dlqEnvelope(ConsumerRecord<String, String> record,
                              InvalidRecord failure) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("originalTopic", record.topic());
        envelope.put("partition", record.partition());
        envelope.put("offset", record.offset());
        envelope.put("key", record.key());
        envelope.put("eventId", failure.eventId());
        envelope.put("tenant", failure.tenant());
        envelope.put("schemaVersion", failure.schemaVersion());
        envelope.put("reasonCode", failure.reasonCode());
        envelope.put("reason", failure.reason());
        envelope.put("originalPayload", record.value());
        envelope.put("failedAt", Instant.now().toString());
        return MAPPER.writeValueAsString(envelope);
    }

    private static String cleanReason(String reason, String fallback) {
        String cleaned = reason == null ? "" : reason.replace('\r', ' ')
                .replace('\n', ' ').replace('\t', ' ').trim();
        if (cleaned.isBlank()) cleaned = fallback;
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private KafkaProducer<String, String> dlq() {
        KafkaProducer<String, String> current = dlqProducer;
        if (current != null) return current;
        synchronized (this) {
            if (dlqProducer == null) {
                dlqProducer = new KafkaProducer<>(KafkaClientSupport.reliableProducer(kafkaProperties.getBootstrap()));
            }
            return dlqProducer;
        }
    }

    private java.util.Properties consumerProperties() {
        return KafkaClientSupport.reliableConsumer(kafkaProperties.getBootstrap(),
                "socp-os-indexer", "earliest", 500);
    }

    private static SearchEvent toEvent(Map<String, Object> value, String fallbackId) {
        Instant timestamp = Instant.now();
        try {
            timestamp = Instant.parse(String.valueOf(value.getOrDefault("timestamp", timestamp)));
        } catch (Exception ignored) {
        }
        Object eventIdValue = value.get("eventId");
        String eventId = String.valueOf(eventIdValue == null ? fallbackId : eventIdValue).trim();
        if (eventId.isBlank() || "null".equalsIgnoreCase(eventId)) {
            throw new IllegalArgumentException("eventId is required for idempotent indexing");
        }
        Map<String, String> fields = stringMap(value.get("fields"));
        String tenant = fields.get("tenant_id");
        String envelopeTenant = value.get("tenantId") == null ? null : String.valueOf(value.get("tenantId"));
        if (tenant == null) tenant = envelopeTenant;
        if (tenant != null && envelopeTenant != null && !tenant.equals(envelopeTenant)) {
            throw new IllegalArgumentException("tenant_id and tenantId do not match");
        }
        if (tenant == null || !com.socp.platform.tenant.context.TenantContext.isValid(tenant)) {
            throw new IllegalArgumentException("tenant_id is required for idempotent indexing");
        }
        // Keep the legacy rule/storage field populated even when a versioned
        // producer only supplies the envelope tenantId.  OpenSearch document
        // identity and older detection queries both rely on this bridge.
        if (!fields.containsKey("tenant_id")) {
            Map<String, String> withTenant = new LinkedHashMap<>(fields);
            withTenant.put("tenant_id", tenant);
            fields = Map.copyOf(withTenant);
        }
        return new SearchEvent(eventId, timestamp,
                String.valueOf(value.getOrDefault("source", "unknown")),
                String.valueOf(value.getOrDefault("host", "unknown")),
                String.valueOf(value.getOrDefault("severity", "INFO")),
                String.valueOf(value.getOrDefault("msg", "")),
                fields, stringMap(value.get("ecs")));
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null && item != null) result.put(String.valueOf(key), String.valueOf(item));
        });
        return Map.copyOf(result);
    }

    private void recordMetric(String stage, long count) {
        if (meterRegistry != null && count > 0) {
            meterRegistry.counter("socp.opensearch.indexer.records", "stage", stage)
                    .increment(count);
        }
    }

    private record PreparedRecord(ConsumerRecord<String, String> record, SearchEvent event,
                                  int bulkIndex, InvalidRecord invalid) {
    }

    record InvalidRecord(String eventId, String tenant, String schemaVersion,
                         String reasonCode, String reason) {
    }

    private record PartitionOutcome(long nextOffset, boolean completed) {
    }

    @PreDestroy
    void stop() {
        running = false;
        KafkaConsumer<String, String> consumer = activeConsumer;
        if (consumer != null) consumer.wakeup();
        if (worker != null) worker.interrupt();
        KafkaProducer<String, String> producer = dlqProducer;
        if (producer != null) producer.close(Duration.ofSeconds(2));
    }
}
