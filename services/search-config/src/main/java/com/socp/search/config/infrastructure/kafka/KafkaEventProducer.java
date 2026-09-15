package com.socp.search.config.infrastructure.kafka;

import com.socp.platform.client.kafka.KafkaTrace;
import com.socp.platform.obs.trace.TracePropagation;
import com.socp.search.config.config.KafkaProperties;
import com.socp.search.config.config.SearchRuntimeRole;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Kafka transport for durable canonical-event Outbox records. A successful
 * return means the broker acknowledged the record; availability probes and
 * fire-and-forget sends are deliberately not part of this boundary.
 */
@Component
@SearchRuntimeRole(SearchRuntimeRole.Role.WORKER)
public class KafkaEventProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventProducer.class);
    private final KafkaProperties properties;

    private volatile KafkaProducer<String, String> producer;

    public KafkaEventProducer() {
        this(new KafkaProperties());
    }

    @Autowired
    public KafkaEventProducer(KafkaProperties properties) {
        this.properties = properties;
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    Properties props = new Properties();
                    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrap());
                    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                    // 可靠性（2026-08-10）：acks=all + 幂等 + 重试，杜绝"发出去但丢了"的静默降级
                    props.put(ProducerConfig.ACKS_CONFIG, "all");
                    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
                    props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
                    props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
                    props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
                    // 探测（partitionsFor）与发送的阻塞上限：broker 不可达时快速失败而非卡 60s
                    props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
                    producer = new KafkaProducer<>(props);
                }
                p = producer;
            }
        }
        return p;
    }

    /**
     * Sends one durable outbox record and waits for the broker acknowledgement.
     * The caller must not mark the outbox row published before this returns true.
     */
    public boolean sendAndAwait(String routingKey, String payload, String traceparent) {
        if (!properties.isEnabled()) return false;
        // The outbox row carries the trace context captured at ingest time,
        // because this thread never saw the producing request. Publishing under
        // a PRODUCER span parented to it makes the publish a real child of the
        // ingest; replaying the stored string verbatim would instead give the
        // consumer a parent that is the ingest span itself, with no hop of its
        // own in between.
        Span span = TracePropagation.startSpan("kafka publish " + properties.getTopic(),
                SpanKind.PRODUCER, TracePropagation.contextFrom(traceparent));
        Throwable failure = null;
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(properties.getTopic(), routingKey, payload);
            KafkaTrace.inject(KafkaTrace.contextOf(span), record.headers());
            producer().send(record).get(30, TimeUnit.SECONDS);
            return true;
        } catch (Exception publishFailure) {
            failure = publishFailure;
            log.warn("Kafka canonical event publish failed routingKey={}: {}",
                    routingKey, publishFailure.getMessage());
            return false;
        } finally {
            TracePropagation.finish(span, failure);
        }
    }

    boolean isEnabled() {
        return properties.isEnabled();
    }

    @PreDestroy
    void stop() {
        KafkaProducer<String, String> current = producer;
        if (current != null) current.close(java.time.Duration.ofSeconds(2));
    }
}
