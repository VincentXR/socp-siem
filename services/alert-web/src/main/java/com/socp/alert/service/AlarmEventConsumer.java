package com.socp.alert.service;

import com.socp.alert.config.AlertKafkaProperties;
import com.socp.alert.domain.Alarm;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.client.kafka.KafkaTrace;
import com.socp.platform.obs.trace.TracePropagation;
import com.socp.platform.tenant.context.TenantContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
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
                applyPolledBatch(consumer, records);
            }
        } catch (RuntimeException failure) {
            log.warn("Alarm event reconciler stopped: {}", failure.getMessage());
        }
    }

    /**
     * Applies one polled batch: rewind it when it must be retried, commit it once
     * the batch has been registered. Separated from {@link #run()} for the same
     * reason as {@link #processBatch}: this is the decision that decides whether a
     * restart replays the batch or skips it, and that is worth testing without a
     * broker.
     */
    void applyPolledBatch(KafkaConsumer<String, String> consumer,
                          ConsumerRecords<String, String> records) {
        if (processBatch(records)) {
            KafkaClientSupport.rewindBatch(consumer, records);
        } else if (!records.isEmpty()) {
            consumer.commitSync();
        }
    }

    /**
     * Registers one polled batch. Returns true when the caller must rewind and
     * retry the batch: either a record broke a rule and the DLQ would not
     * acknowledge it, or delivery failed transiently and the batch is the only
     * durable record of the work.
     * <p>
     * Separated from {@link #run()} so the retry, dead-letter and span
     * lifecycle decisions can be tested without a broker.
     */
    boolean processBatch(ConsumerRecords<String, String> records) {
        boolean retry = false;
        for (var record : records) {
            restoreTrace(record.headers().lastHeader("traceparent"));
            Throwable failure = null;
            Span span = KafkaTrace.startConsume("alarm-register " + record.topic(),
                    record.headers());
            try (Scope scope = KafkaTrace.contextOf(span).makeCurrent()) {
                registerEvent(record.value());
            } catch (IllegalArgumentException | JsonProcessingException invalid) {
                failure = invalid;
                if (!toDlqAndAwait(record.key(), record.value(), record.headers())) {
                    retry = true;
                    break;
                }
                log.warn("Invalid alarm event moved to DLQ alarmId={}: {}", record.key(), invalid.getMessage());
            } catch (RuntimeException transientFailure) {
                failure = transientFailure;
                log.warn("Alarm delivery registration failed; Kafka batch will retry: {}",
                        transientFailure.getMessage());
                retry = true;
                break;
            } finally {
                TracePropagation.finish(span, failure);
                TenantContext.clear();
                MDC.remove("traceId");
            }
        }
        return retry;
    }

    void registerEvent(String raw) throws JsonProcessingException {
        Map<String, Object> payload = AlarmPayloadCodec.read(raw);
        String alarmId = text(payload.get("id"));
        String tenant = text(payload.get("tenantId"));
        if (tenant == null) tenant = text(payload.get("tenant_id"));
        if (tenant == null) throw new IllegalArgumentException("missing alarm tenant");
        if (!TenantContext.isValid(tenant)) throw new IllegalArgumentException("invalid alarm tenant");
        if (alarmId == null) throw new IllegalArgumentException("missing alarm id");
        TenantContext.set(tenant);
        registrar.register(tenant, alarmId, raw);
    }

    private void restoreTrace(org.apache.kafka.common.header.Header traceparent) {
        if (traceparent == null) return;
        String traceId = com.socp.platform.obs.web.TraceIdFilter.parseTraceId(
                new String(traceparent.value(), StandardCharsets.UTF_8));
        if (traceId != null) MDC.put("traceId", traceId);
    }

    private boolean toDlqAndAwait(String alarmId, String raw, Headers sourceHeaders) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic + "-dlq",
                    alarmId == null ? "unknown" : alarmId, raw);
            // The dead-letter entry inherits the trace of the record it replaces,
            // so an operator following the DLQ lands in the trace that failed.
            KafkaTrace.inject(KafkaTrace.extract(sourceHeaders), record.headers());
            KafkaClientSupport.sendAndAwait(dlq(), record, Duration.ofSeconds(10));
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
