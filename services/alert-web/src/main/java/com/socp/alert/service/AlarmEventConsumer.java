package com.socp.alert.service;

import com.socp.alert.config.AlertKafkaProperties;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.socp.platform.client.kafka.KafkaClientSupport;
import com.socp.platform.client.kafka.KafkaTrace;
import com.socp.platform.obs.trace.TracePropagation;
import com.socp.platform.tenant.context.TenantContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reconciles alarm events into idempotent, database-backed delivery intents. */
@Component
public class AlarmEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlarmEventConsumer.class);

    private static final String GROUP_ID = "socp-alarm-delivery-registration";
    private static final long POLL_INTERVAL_MS = 500L;

    private final String bootstrap;
    private final String topic;
    private final boolean enabled;
    private final AlarmDeliveryRegistrar registrar;
    private final AlertPerformanceMetrics metrics;
    private volatile KafkaProducer<String, String> dlqProducer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile KafkaConsumer<String, String> activeConsumer;

    // Bounded-retry pacing knobs. They are fields rather than constants so a
    // test can pin the ceiling without waiting on real back-off intervals.
    private long retryDelayMs = 1_000L;
    private long retryMaxMs = 30_000L;
    private int retryCeilingBatches = 5;
    private long maxPollIntervalMs = 1_800_000L;

    @Autowired
    public AlarmEventConsumer(AlarmDeliveryRegistrar registrar, AlertKafkaProperties properties,
                              AlertPerformanceMetrics metrics) {
        this.registrar = registrar;
        this.bootstrap = properties.getBootstrap();
        this.topic = properties.getAlarmTopic();
        this.enabled = properties.isEnabled();
        this.metrics = metrics;
    }

    AlarmEventConsumer(AlarmDeliveryRegistrar registrar, AlertKafkaProperties properties) {
        this(registrar, properties, null);
    }

    AlarmEventConsumer(AlarmDeliveryRegistrar registrar) {
        this(registrar, new AlertKafkaProperties());
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        running.set(true);
        worker = Thread.ofPlatform().name("alarm-event-consumer").daemon(true).start(this::run);
        log.info("Alarm event reconciler started topic={}", topic);
    }

    /**
     * Supervise the Kafka session so a single rebalance, commit failure or poll
     * error restarts the reconciler with bounded back-off instead of silently
     * killing the only polling thread while the JVM (and health probes) stay green.
     */
    private void run() {
        long restartDelay = retryDelayMs;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                runConsumerSession();
                restartDelay = retryDelayMs;
            } catch (WakeupException wakeup) {
                if (running.get()) {
                    log.warn("Alarm event reconciler was unexpectedly woken", wakeup);
                }
                return;
            } catch (RuntimeException failure) {
                if (!running.get() || Thread.currentThread().isInterrupted()) return;
                log.error("Alarm event reconciler session failed; restarting in {}ms: {}",
                        restartDelay, failure.getMessage(), failure);
                if (metrics != null) metrics.reconcilerSessionRestart();
            }
            if (!running.get()) break;
            if (!sleepRetry(restartDelay)) break;
            restartDelay = nextRetryDelayMs(restartDelay);
        }
    }

    private void runConsumerSession() {
        var props = KafkaClientSupport.reliableConsumer(bootstrap, GROUP_ID, "earliest", 200);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, String.valueOf(maxPollIntervalMs));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            activeConsumer = consumer;
            consumer.subscribe(List.of(topic), new SessionRebalanceListener());
            long retryDelay = retryDelayMs;
            int consecutiveRetryBatches = 0;
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                var records = consumer.poll(Duration.ofMillis(POLL_INTERVAL_MS));
                if (!applyPolledBatch(consumer, records)) {
                    consecutiveRetryBatches = 0;
                    retryDelay = retryDelayMs;
                    continue;
                }
                consecutiveRetryBatches++;
                if (consecutiveRetryBatches >= retryCeilingBatches) {
                    log.error("Alarm delivery registration wedged on {} consecutive batches; "
                                    + "holding the batch uncommitted at {}ms back-off so it is "
                                    + "redelivered on the next rebalance instead of hot-spinning",
                            consecutiveRetryBatches, retryMaxMs);
                    if (metrics != null) metrics.reconcilerBackoff("ceiling");
                    if (!sleepRetry(retryMaxMs)) break;
                    continue;
                }
                if (metrics != null) metrics.reconcilerBackoff("retry");
                if (!sleepRetry(retryDelay)) break;
                retryDelay = nextRetryDelayMs(retryDelay);
            }
        } finally {
            activeConsumer = null;
        }
    }

    /**
     * Applies one polled batch: rewind it when it must be retried, commit it once
     * the batch has been registered. Returns true when the batch was rewound and
     * must be retried. Separated from {@link #run()} for the same reason as
     * {@link #processBatch}: this is the decision that determines whether a
     * restart replays the batch or skips it, and that is worth testing without a
     * broker.
     */
    boolean applyPolledBatch(KafkaConsumer<String, String> consumer,
                             ConsumerRecords<String, String> records) {
        if (processBatch(records)) {
            KafkaClientSupport.rewindBatch(consumer, records);
            return true;
        }
        if (!records.isEmpty()) {
            // commitSync raises CommitFailedException once the partitions are
            // revoked, which the supervisor turns into a clean re-join; the offset
            // stays uncommitted so the batch is redelivered, never dropped.
            consumer.commitSync();
        }
        return false;
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

    /** Doubles the delay toward {@link #retryMaxMs}, never below {@link #retryDelayMs}. */
    long nextRetryDelayMs(long current) {
        long doubled = current <= 0 ? retryDelayMs : current * 2;
        return Math.min(retryMaxMs, Math.max(retryDelayMs, doubled));
    }

    private boolean sleepRetry(long delayMs) {
        if (delayMs <= 0) {
            return running.get() && !Thread.currentThread().isInterrupted();
        }
        try {
            Thread.sleep(delayMs);
            return running.get() && !Thread.currentThread().isInterrupted();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        KafkaConsumer<String, String> consumer = activeConsumer;
        if (consumer != null) consumer.wakeup();
        Thread handle = worker;
        if (handle != null) handle.interrupt();
        KafkaProducer<String, String> producer = dlqProducer;
        if (producer != null) producer.close(Duration.ofSeconds(5));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static final class SessionRebalanceListener implements ConsumerRebalanceListener {
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            if (!partitions.isEmpty()) log.info("Alarm delivery partitions revoked: {}", partitions);
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            if (!partitions.isEmpty()) log.info("Alarm delivery partitions assigned: {}", partitions);
        }
    }
}
