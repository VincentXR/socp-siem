package com.socp.detect.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.tenant.TenantContext;
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

/** Per-instance, tenant-aware rule cache invalidation consumer. */
@Component
public class RuleChangeListener {

    private static final Logger log = LoggerFactory.getLogger(RuleChangeListener.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final DetectEngineService engineService;

    @Value("${socp.kafka.bootstrap:localhost:9092}")
    private String bootstrap;

    @Value("${socp.kafka.rule-topic:socp-rule-changes}")
    private String topic;

    @Value("${socp.kafka.enabled:true}")
    private boolean enabled;

    @Value("${socp.instance-id:${random.uuid}}")
    private String instanceId;

    private volatile boolean running;
    private volatile KafkaConsumer<String, String> activeConsumer;
    private volatile KafkaProducer<String, String> dlqProducer;
    private Thread worker;

    public RuleChangeListener(DetectEngineService engineService) {
        this.engineService = engineService;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        running = true;
        worker = Thread.ofPlatform().name("rule-change-consumer").daemon(true).start(this::runLoop);
        log.info("Rule-change listener started topic={} instance={}", topic, instanceId);
    }

    private void runLoop() {
        while (running) {
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
                    KafkaClientSupport.reliableConsumer(bootstrap,
                            "socp-rule-change-" + instanceId, "latest", 100))) {
                activeConsumer = consumer;
                consumer.subscribe(List.of(topic));
                consume(consumer);
            } catch (org.apache.kafka.common.errors.WakeupException wakeup) {
                if (running) log.warn("Rule-change consumer was unexpectedly woken");
            } catch (RuntimeException failure) {
                if (running) {
                    log.warn("Rule-change listener failed; restarting: {}", failure.getMessage());
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
                    processRecord(record.value());
                } catch (InvalidRuleChangeException invalid) {
                    if (!toDlqAndAwait(record.key(), record.value())) {
                        retryBatch = true;
                        break;
                    }
                    log.warn("Invalid rule-change event moved to DLQ key={}: {}",
                            record.key(), invalid.getMessage());
                } catch (RuntimeException transientFailure) {
                    log.warn("Rule cache reload failed; Kafka batch will retry: {}",
                            transientFailure.getMessage());
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

    @SuppressWarnings("unchecked")
    void processRecord(String raw) {
        Map<String, Object> event;
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty rule-change payload");
            event = MAPPER.readValue(raw, Map.class);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new InvalidRuleChangeException(invalid.getMessage(), invalid);
        }
        String eventId = text(event.get("eventId"));
        String tenant = text(event.get("tenantId"));
        String ruleId = text(event.get("ruleId"));
        String action = text(event.get("action"));
        if (eventId == null) throw invalid("rule-change event id is required");
        if (!TenantContext.isValid(tenant)) throw invalid("invalid rule-change tenant");
        if (ruleId == null || action == null) {
            throw invalid("rule-change ruleId and action are required");
        }
        TenantContext.set(tenant);
        try {
            engineService.reload();
        } finally {
            TenantContext.clear();
        }
        log.info("Rule cache reloaded tenant={} ruleId={} action={}", tenant, ruleId, action);
    }

    private boolean toDlqAndAwait(String key, String raw) {
        try {
            KafkaClientSupport.sendAndAwait(dlq(), topic + "-dlq", key, raw, Duration.ofSeconds(10));
            return true;
        } catch (RuntimeException failure) {
            log.warn("Rule-change DLQ acknowledgement failed key={}: {}", key, failure.getMessage());
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

    private static InvalidRuleChangeException invalid(String message) {
        return new InvalidRuleChangeException(message, new IllegalArgumentException(message));
    }

    static final class InvalidRuleChangeException extends RuntimeException {
        InvalidRuleChangeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
