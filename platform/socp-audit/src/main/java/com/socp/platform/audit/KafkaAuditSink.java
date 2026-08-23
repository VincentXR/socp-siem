package com.socp.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Publishes audit records to the shared audit topic and waits for broker acknowledgement. */
public class KafkaAuditSink implements AuditSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditSink.class);

    private final KafkaTemplate<String, String> template;
    private final String topic;
    private final boolean failClosed;
    private final ObjectMapper mapper;

    public KafkaAuditSink(KafkaTemplate<String, String> template, String topic, boolean failClosed) {
        this(template, topic, failClosed, new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    KafkaAuditSink(KafkaTemplate<String, String> template, String topic, boolean failClosed,
                   ObjectMapper mapper) {
        this.template = Objects.requireNonNull(template, "template");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.failClosed = failClosed;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void publish(AuditRecord record) {
        try {
            String payload = mapper.writeValueAsString(record);
            template.send(topic, record.eventId(), payload).get(5, TimeUnit.SECONDS);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("cannot serialize audit record", failure);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failClosed) {
                throw new IllegalStateException("audit broker acknowledgement failed", failure);
            }
            log.error("Audit delivery failed eventId={} action={}: {}",
                    record.eventId(), record.action(), failure.toString());
        }
    }
}
