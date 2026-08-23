package com.socp.soc.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Persists audit events with durable event-id idempotency and manual Kafka offsets. */
@Component
public class AuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.audit-topic:socp-audit}")
    private String topic;

    @Value("${socp.kafka.audit-enabled:true}")
    private boolean enabled;

    private final AuditRepository repository;
    private volatile KafkaProducer<String, String> dlqProducer;
    private volatile Thread worker;

    public AuditConsumer(AuditRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        worker = Thread.ofPlatform().name("audit-consumer").daemon(true).start(this::run);
        log.info("Audit consumer started bootstrap={} topic={}", bootstrap, topic);
    }

    private void run() {
        var props = KafkaClientSupport.reliableConsumer(bootstrap,
                "socp-audit-sink", "earliest", 200);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (records.isEmpty()) continue;
                boolean retryBatch = false;
                for (var record : records) {
                    try {
                        processRecord(record.key(), record.value());
                    } catch (InvalidAuditEventException invalid) {
                        if (!toDlqAndAwait(record.key(), record.value())) {
                            retryBatch = true;
                            break;
                        }
                        log.warn("Invalid audit event moved to DLQ key={}: {}", record.key(), invalid.getMessage());
                    } catch (RuntimeException persistenceFailure) {
                        log.warn("Audit persistence failed; Kafka batch will retry key={}: {}",
                                record.key(), persistenceFailure.getMessage());
                        retryBatch = true;
                        break;
                    }
                }
                if (retryBatch) {
                    KafkaClientSupport.rewindBatch(consumer, records);
                } else {
                    consumer.commitSync();
                }
            }
        } catch (RuntimeException failure) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Audit consumer stopped unexpectedly", failure);
            }
        }
    }

    void processRecord(String key, String raw) {
        AuditEntity entity;
        try {
            entity = parse(key, raw);
        } catch (Exception invalid) {
            throw new InvalidAuditEventException(invalid.getMessage(), invalid);
        }
        if (repository.existsByEventId(entity.getEventId())) return;
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException race) {
            if (!repository.existsByEventId(entity.getEventId())) throw race;
        }
    }

    @SuppressWarnings("unchecked")
    AuditEntity parse(String key, String raw) throws Exception {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty audit payload");
        Map<String, Object> values = MAPPER.readValue(raw, Map.class);
        String eventId = text(values.get("eventId"));
        if (eventId == null) eventId = text(key);
        if (eventId == null) eventId = legacyEventId(raw);
        String tenantId = text(values.get("tenantId"));
        if (tenantId == null) tenantId = "default";
        String action = text(values.get("action"));
        String operator = text(values.get("operator"));
        String target = text(values.get("target"));
        String result = text(values.get("result"));
        if (!TenantContext.isValid(tenantId)) throw new IllegalArgumentException("invalid audit tenant");
        if (action == null) throw new IllegalArgumentException("audit event is missing action");
        requireLength("eventId", eventId, 128);
        requireLength("action", action, 128);
        requireLength("operator", operator, 128);
        requireLength("target", target, 512);
        requireLength("result", result, 64);
        return new AuditEntity(
                eventId,
                tenantId,
                action,
                operator == null ? "system" : operator,
                target,
                result == null ? "OK" : result,
                parseTimestamp(values.get("timestamp")));
    }

    private boolean toDlqAndAwait(String key, String raw) {
        try {
            KafkaClientSupport.sendAndAwait(dlq(), topic + "-dlq", key, raw, Duration.ofSeconds(10));
            return true;
        } catch (RuntimeException failure) {
            log.warn("Audit DLQ acknowledgement failed key={}: {}", key, failure.getMessage());
            return false;
        }
    }

    private KafkaProducer<String, String> dlq() {
        KafkaProducer<String, String> current = dlqProducer;
        if (current != null) return current;
        synchronized (this) {
            if (dlqProducer == null) {
                dlqProducer = new KafkaProducer<>(KafkaClientSupport.reliableProducer(bootstrap));
            }
            return dlqProducer;
        }
    }

    @PreDestroy
    void stop() {
        Thread currentWorker = worker;
        if (currentWorker != null) currentWorker.interrupt();
        KafkaProducer<String, String> producer = dlqProducer;
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static void requireLength(String field, String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException("audit " + field + " is too long");
        }
    }

    private static Instant parseTimestamp(Object timestamp) {
        if (timestamp == null) return Instant.now();
        if (timestamp instanceof Number number) {
            long value = number.longValue();
            return value < 100_000_000_000L
                    ? Instant.ofEpochSecond(value)
                    : Instant.ofEpochMilli(value);
        }
        return Instant.parse(String.valueOf(timestamp));
    }

    private static String legacyEventId(String raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        return "legacy-" + HexFormat.of().formatHex(digest);
    }

    static final class InvalidAuditEventException extends RuntimeException {
        InvalidAuditEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
