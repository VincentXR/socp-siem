package com.socp.detect.web.persistence.entity;

import com.socp.detect.web.persistence.store.DetectionEventStatus;
import com.socp.rule.partition.DetectionDelivery;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Accepted detection-delivery journal; source evidence identity is retained separately. */
@Entity
@Table(name = "t_detection_event", indexes = {
        @Index(name = "idx_detection_event_occurred", columnList = "occurred_at"),
        @Index(name = "idx_detection_event_source", columnList = "tenant_id,source_event_id"),
        @Index(name = "idx_detection_event_delivery_position",
                columnList = "delivery_topic,delivery_partition,delivery_offset")
})
public class DetectionEventEntity {

    @Id
    @Column(name = "event_id", length = 128)
    private String storageId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "delivery_id", length = 128, nullable = false)
    private String deliveryId;

    @Column(name = "source_event_id", length = 128, nullable = false)
    private String sourceEventId;

    @Column(name = "routing_version", length = 64, nullable = false)
    private String routingVersion;

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

    @Column(name = "source_topic", length = 255)
    private String sourceTopic;

    @Column(name = "source_partition")
    private Integer sourcePartition;

    @Column(name = "source_offset")
    private Long sourceOffset;

    /** Delivery transport position. The legacy Java accessors below return this position only. */
    @Column(name = "delivery_partition")
    private Integer kafkaPartition;

    @Column(name = "delivery_offset")
    private Long kafkaOffset;

    @Column(name = "delivery_topic", length = 255)
    private String deliveryTopic;

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

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson = "{}";

    public DetectionEventEntity() {
    }

    public DetectionEventEntity(String eventId, String source, String host, String raw,
                                String fieldsJson, String severity, Instant occurredAt) {
        this(eventId, source, host, raw, fieldsJson, severity, occurredAt, null, null, null);
    }

    public DetectionEventEntity(String eventId, String source, String host, String raw,
                                String fieldsJson, String severity, Instant occurredAt,
                                Integer partition, Long offset, String routingKey) {
        this("default", eventId, eventId, "legacy-v1", source, host, raw, fieldsJson,
                severity, occurredAt, null, partition, offset, null, partition, offset, routingKey);
    }

    public DetectionEventEntity(String tenantId, String eventId, String source, String host,
                                String raw, String fieldsJson, String severity, Instant occurredAt,
                                Integer partition, Long offset, String routingKey) {
        this(tenantId, eventId, eventId, "legacy-v1", source, host, raw, fieldsJson,
                severity, occurredAt, null, partition, offset, null, partition, offset, routingKey);
    }

    public DetectionEventEntity(String tenantId, String deliveryId, String sourceEventId,
                                String routingVersion, String source, String host,
                                String raw, String fieldsJson, String severity, Instant occurredAt,
                                String sourceTopic, Integer sourcePartition, Long sourceOffset,
                                String deliveryTopic, Integer deliveryPartition, Long deliveryOffset,
                                String routingKey) {
        this.tenantId = tenantId;
        this.deliveryId = deliveryId;
        this.sourceEventId = sourceEventId;
        this.routingVersion = routingVersion == null || routingVersion.isBlank()
                ? "legacy-v1" : routingVersion;
        this.storageId = java.util.UUID.nameUUIDFromBytes((tenantId + "|" + deliveryId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        this.source = source;
        this.host = host;
        this.raw = raw;
        this.fieldsJson = fieldsJson;
        this.severity = severity;
        this.occurredAt = occurredAt;
        this.sourceTopic = sourceTopic;
        this.sourcePartition = sourcePartition;
        this.sourceOffset = sourceOffset;
        this.deliveryTopic = deliveryTopic;
        this.kafkaPartition = deliveryPartition;
        this.kafkaOffset = deliveryOffset;
        this.routingKey = routingKey;
        this.status = DetectionEventStatus.PENDING.name();
    }

    public String getEventId() { return sourceEventId; }
    public String getStorageId() { return storageId; }
    public String getTenantId() { return tenantId; }
    public String getDeliveryId() { return deliveryId; }
    public String getSourceEventId() { return sourceEventId; }
    public String getRoutingVersion() { return routingVersion; }
    public String getSource() { return source; }
    public String getHost() { return host; }
    public String getRaw() { return raw; }
    public String getFieldsJson() { return fieldsJson; }
    public String getSeverity() { return severity; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getSourceTopic() { return sourceTopic; }
    public Integer getSourcePartition() { return sourcePartition; }
    public Long getSourceOffset() { return sourceOffset; }
    public String getDeliveryTopic() { return deliveryTopic; }
    public Integer getDeliveryPartition() { return kafkaPartition; }
    public Long getDeliveryOffset() { return kafkaOffset; }
    /** Compatibility name: this is the delivery partition, never the source partition. */
    public Integer getKafkaPartition() { return kafkaPartition; }
    /** Compatibility name: this is the delivery offset, never the source offset. */
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
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson == null ? "{}" : resultJson; }
}
