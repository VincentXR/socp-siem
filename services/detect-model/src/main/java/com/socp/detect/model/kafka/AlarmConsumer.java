package com.socp.detect.model.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.model.service.AnalyzeService;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.rule.model.Severity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Reliable tenant-aware consumer for secondary alarm analysis. */
@Component
public class AlarmConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmConsumer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.alarm-topic:socp-alarm-original}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    private final AnalyzeService analyzeService;
    private volatile boolean running;
    private volatile KafkaConsumer<String, String> activeConsumer;
    private volatile KafkaProducer<String, String> dlqProducer;
    private Thread worker;

    public AlarmConsumer(AnalyzeService analyzeService) {
        this.analyzeService = analyzeService;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        running = true;
        worker = Thread.ofPlatform().name("alarm-consumer").daemon(true).start(this::runLoop);
        log.info("Secondary alarm consumer started bootstrap={} topic={}", bootstrap, topic);
    }

    private void runLoop() {
        while (running) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                    KafkaClientSupport.reliableConsumer(bootstrap,
                            "socp-detect-model", "earliest", 200))) {
                activeConsumer = consumer;
                consumer.subscribe(List.of(topic));
                consume(consumer);
            } catch (org.apache.kafka.common.errors.WakeupException wakeup) {
                if (running) log.warn("Secondary alarm consumer was unexpectedly woken");
            } catch (RuntimeException failure) {
                if (running) {
                    log.warn("Secondary alarm consumer failed; restarting: {}", failure.getMessage());
                    backoff();
                }
            } finally {
                activeConsumer = null;
            }
        }
    }

    private void consume(KafkaConsumer<String, String> consumer) {
        while (running) {
            var records = consumer.poll(Duration.ofMillis(500));
            if (records.isEmpty()) continue;
            boolean retryBatch = false;
            for (var record : records) {
                try {
                    processRecord(record.key(), record.value());
                } catch (InvalidAlarmEventException invalid) {
                    if (!toDlqAndAwait(record.key(), record.value())) {
                        retryBatch = true;
                        break;
                    }
                    log.warn("Invalid secondary alarm moved to DLQ alertId={}: {}",
                            record.key(), invalid.getMessage());
                } catch (RuntimeException transientFailure) {
                    log.warn("Secondary analysis failed; Kafka batch will retry alertId={}: {}",
                            record.key(), transientFailure.getMessage());
                    retryBatch = true;
                    break;
                }
            }
            if (retryBatch) {
                KafkaClientSupport.rewindBatch(consumer, records);
                backoff();
            } else {
                consumer.commitSync();
            }
        }
    }

    void processRecord(String key, String raw) {
        ParsedAlarm parsed = parse(key, raw);
        TenantContext.set(parsed.tenant());
        try {
            analyzeService.analyze(parsed.payload());
        } finally {
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static ParsedAlarm parse(String key, String raw) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty alarm payload");
            Map<String, Object> alarm = MAPPER.readValue(raw, Map.class);
            String alarmId = text(alarm.get("id"));
            if (alarmId == null) alarmId = text(key);
            if (alarmId == null) throw new IllegalArgumentException("alarm id is required");
            String tenant = text(alarm.get("tenantId"));
            if (tenant == null) tenant = text(alarm.get("tenant_id"));
            if (tenant == null) throw new IllegalArgumentException("alarm tenant is required");
            if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid alarm tenant");
            String severity = text(alarm.get("severity"));
            if (severity != null) Severity.valueOf(severity.toUpperCase(java.util.Locale.ROOT));
            return new ParsedAlarm(tenant, alarm);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new InvalidAlarmEventException(invalid.getMessage(), invalid);
        }
    }

    private boolean toDlqAndAwait(String alertId, String raw) {
        try {
            KafkaClientSupport.sendAndAwait(dlq(), topic + "-dlq", alertId, raw, Duration.ofSeconds(10));
            return true;
        } catch (RuntimeException failure) {
            log.warn("Secondary alarm DLQ acknowledgement failed alertId={}: {}",
                    alertId, failure.getMessage());
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

    private void backoff() {
        try {
            Thread.sleep(1_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        KafkaConsumer<String, String> consumer = activeConsumer;
        if (consumer != null) consumer.wakeup();
        if (worker != null) worker.interrupt();
        KafkaProducer<String, String> producer = dlqProducer;
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() || "null".equalsIgnoreCase(result) ? null : result;
    }

    private record ParsedAlarm(String tenant, Map<String, Object> payload) {
    }

    static final class InvalidAlarmEventException extends RuntimeException {
        InvalidAlarmEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
