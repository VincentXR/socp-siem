package com.socp.alert.service;

import com.socp.alert.api.*;
import com.socp.alert.config.AlertKafkaProperties;
import com.socp.alert.domain.*;
import com.socp.alert.repository.*;
import com.socp.alert.service.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Reconciles alarm events into idempotent, database-backed delivery intents. */
@Component
public class AlarmEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventConsumer.class);

    private final String bootstrap;
    private final String topic;
    private final boolean enabled;
    private final AlarmDeliveryRegistrar registrar;
    private volatile KafkaProducer<String, String> dlqProducer;

    @Autowired
    public AlarmEventConsumer(AlarmDeliveryRegistrar registrar, AlertKafkaProperties properties) {
        this.registrar = registrar;
        this.bootstrap = properties.getBootstrap();
        this.topic = properties.getAlarmTopic();
        this.enabled = properties.isEnabled();
    }

    AlarmEventConsumer(AlarmDeliveryRegistrar registrar) {
        this(registrar, new AlertKafkaProperties());
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        Thread.ofPlatform().name("alarm-event-consumer").daemon(true).start(this::run);
        log.info("Alarm event reconciler started topic={}", topic);
    }

    private void run() {
        var props = KafkaClientSupport.reliableConsumer(bootstrap,
                "socp-alarm-delivery-registration", "earliest", 200);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (!Thread.currentThread().isInterrupted()) {
                var records = consumer.poll(Duration.ofMillis(500));
                boolean retry = false;
                for (var record : records) {
                    restoreTrace(record.headers().lastHeader("traceparent"));
                    try {
                        registerEvent(record.value());
                    } catch (IllegalArgumentException | JsonProcessingException invalid) {
                        if (!toDlqAndAwait(record.key(), record.value())) {
                            retry = true;
                            break;
                        }
                        log.warn("Invalid alarm event moved to DLQ alarmId={}: {}", record.key(), invalid.getMessage());
                    } catch (RuntimeException transientFailure) {
                        log.warn("Alarm delivery registration failed; Kafka batch will retry: {}",
                                transientFailure.getMessage());
                        retry = true;
                        break;
                    } finally {
                        TenantContext.clear();
                        MDC.remove("traceId");
                    }
                }
                if (retry) {
                    KafkaClientSupport.rewindBatch(consumer, records);
                } else if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Alarm event reconciler stopped: {}", failure.getMessage());
        }
    }

    void registerEvent(String raw) throws JsonProcessingException {
        Map<String, Object> payload = AlarmPayloadCodec.read(raw);
        String alarmId = text(payload.get("id"));
        String tenant = text(payload.getOrDefault("tenantId", payload.getOrDefault("tenant_id", "default")));
        if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid alarm tenant");
        if (alarmId == null) throw new IllegalArgumentException("missing alarm id");
        TenantContext.set(tenant);
        registrar.register(tenant, alarmId, raw);
    }

    private void restoreTrace(org.apache.kafka.common.header.Header traceparent) {
        if (traceparent == null) return;
        String traceId = com.socp.platform.obs.TraceIdFilter.parseTraceId(
                new String(traceparent.value(), StandardCharsets.UTF_8));
        if (traceId != null) MDC.put("traceId", traceId);
    }

    private boolean toDlqAndAwait(String alarmId, String raw) {
        try {
            KafkaClientSupport.sendAndAwait(dlq(), topic + "-dlq", alarmId, raw, Duration.ofSeconds(10));
            return true;
        } catch (RuntimeException failure) {
            log.warn("Alarm DLQ acknowledgement failed alarmId={}: {}", alarmId, failure.getMessage());
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
        KafkaProducer<String, String> producer = dlqProducer;
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
