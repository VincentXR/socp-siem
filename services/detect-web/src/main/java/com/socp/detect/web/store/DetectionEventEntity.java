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
    private String storageId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "source_event_id", length = 128, nullable = false)
    private String sourceEventId;

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

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "status_reason", length = 1024)
    private String statusReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    public DetectionEventEntity() {
    }

    public DetectionEventEntity(String eventId, String source, String host, String raw,
                                String fieldsJson, String severity, Instant occurredAt) {
        this(eventId, source, host, raw, fieldsJson, severity, occurredAt, null, null, null);
    }

    public DetectionEventEntity(String eventId, String source, String host, String raw,
                                String fieldsJson, String severity, Instant occurredAt,
                                Integer kafkaPartition, Long kafkaOffset, String routingKey) {
        this("default", eventId, source, host, raw, fieldsJson, severity, occurredAt,
                kafkaPartition, kafkaOffset, routingKey);
    }

    public DetectionEventEntity(String tenantId, String eventId, String source, String host,
                                String raw, String fieldsJson, String severity, Instant occurredAt,
                                Integer kafkaPartition, Long kafkaOffset, String routingKey) {
        this.tenantId = tenantId;
        this.sourceEventId = eventId;
        this.storageId = java.util.UUID.nameUUIDFromBytes((tenantId + "|" + eventId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        this.source = source;
        this.host = host;
        this.raw = raw;
        this.fieldsJson = fieldsJson;
        this.severity = severity;
        this.occurredAt = occurredAt;
        this.kafkaPartition = kafkaPartition;
        this.kafkaOffset = kafkaOffset;
        this.routingKey = routingKey;
        this.status = DetectionEventStatus.PENDING.name();
    }

    public String getEventId() { return sourceEventId; }
    public String getStorageId() { return storageId; }
    public String getTenantId() { return tenantId; }
    public String getSource() { return source; }
    public String getHost() { return host; }
    public String getRaw() { return raw; }
    public String getFieldsJson() { return fieldsJson; }
    public String getSeverity() { return severity; }
    public Instant getOccurredAt() { return occurredAt; }
    public Integer getKafkaPartition() { return kafkaPartition; }
    public Long getKafkaOffset() { return kafkaOffset; }
    public String getRoutingKey() { return routingKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getDeadLetteredAt() { return deadLetteredAt; }
    public void setDeadLetteredAt(Instant deadLetteredAt) { this.deadLetteredAt = deadLetteredAt; }
}
