package com.socp.detect.web.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Accepted canonical event journal used to rebuild stateful detection windows. */
@Entity
@Table(name = "t_detection_event", indexes = {
        @Index(name = "idx_detection_event_occurred", columnList = "occurred_at")
})
public class DetectionEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(name = "raw_event", length = 8192)
    private String raw;

    @Column(name = "fields_json", columnDefinition = "TEXT", nullable = false)
    private String fieldsJson;

    @Column(nullable = false, length = 16)
    private String severity;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "kafka_partition")
    private Integer kafkaPartition;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    @Column(name = "routing_key", length = 255)
    private String routingKey;

    public DetectionEventEntity() {
    }

    public DetectionEventEntity(String eventId, String source, String host, String raw,
                                String fieldsJson, String severity, Instant occurredAt) {
        this(eventId, source, host, raw, fieldsJson, severity, occurredAt, null, null, null);
    }

    public DetectionEventEntity(String eventId, String source, String host, String raw,
                                String fieldsJson, String severity, Instant occurredAt,
                                Integer kafkaPartition, Long kafkaOffset, String routingKey) {
        this.eventId = eventId;
        this.source = source;
        this.host = host;
        this.raw = raw;
        this.fieldsJson = fieldsJson;
        this.severity = severity;
        this.occurredAt = occurredAt;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.routingKey = routingKey;
    }

    public String getEventId() { return eventId; }
    public String getSource() { return source; }
    public String getHost() { return host; }
    public String getRaw() { return raw; }
    public String getFieldsJson() { return fieldsJson; }
    public String getSeverity() { return severity; }
    public Instant getOccurredAt() { return occurredAt; }
    public Integer getKafkaPartition() { return kafkaPartition; }
    public Long getKafkaOffset() { return kafkaOffset; }
    public String getRoutingKey() { return routingKey; }
}
