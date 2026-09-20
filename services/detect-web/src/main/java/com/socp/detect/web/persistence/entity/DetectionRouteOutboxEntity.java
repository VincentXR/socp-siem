package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable canonical-source -> routed-detection hand-off. */
@Entity
@Table(name = "t_detection_route_outbox", indexes = {
        @Index(name = "idx_detection_route_outbox_due",
                columnList = "status,next_attempt_at,created_at"),
        @Index(name = "idx_detection_route_outbox_source",
                columnList = "source_topic,source_partition,source_offset"),
        @Index(name = "idx_detection_route_outbox_delivery",
                columnList = "delivery_topic,delivery_partition,delivery_offset")
})
public class DetectionRouteOutboxEntity {

    @Id
    @Column(name = "delivery_id", length = 64)
    private String deliveryId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "source_event_id", nullable = false, length = 255)
    private String sourceEventId;

    @Column(name = "routing_version", nullable = false, length = 64)
    private String routingVersion;

    @Column(name = "plan_version", nullable = false, length = 64)
    private String planVersion;

    @Column(name = "route_kind", nullable = false, length = 16)
    private String routeKind;

    @Column(name = "route_dimension", nullable = false, length = 255)
    private String routeDimension;

    @Column(name = "route_value", nullable = false, length = 1024)
    private String routeValue;

    @Column(name = "routing_key", nullable = false, length = 255)
    private String routingKey;

    @Column(name = "source_topic", nullable = false, length = 255)
    private String sourceTopic;

    @Column(name = "source_partition", nullable = false)
    private int sourcePartition;

    @Column(name = "source_offset", nullable = false)
    private long sourceOffset;

    @Column(name = "delivery_topic", nullable = false, length = 255)
    private String deliveryTopic;

    @Column(name = "delivery_partition")
    private Integer deliveryPartition;

    @Column(name = "delivery_offset")
    private Long deliveryOffset;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    protected DetectionRouteOutboxEntity() {
    }

    public DetectionRouteOutboxEntity(String deliveryId, String tenantId, String sourceEventId,
                                      String routingVersion, String planVersion,
                                      String routeKind, String routeDimension, String routeValue,
                                      String routingKey, String sourceTopic, int sourcePartition,
                                      long sourceOffset, String deliveryTopic, String payload,
                                      Instant now) {
        this.deliveryId = deliveryId;
        this.tenantId = tenantId;
        this.sourceEventId = sourceEventId;
        this.routingVersion = routingVersion;
        this.planVersion = planVersion;
        this.routeKind = routeKind;
        this.routeDimension = routeDimension;
        this.routeValue = routeValue;
        this.routingKey = routingKey;
        this.sourceTopic = sourceTopic;
        this.sourcePartition = sourcePartition;
        this.sourceOffset = sourceOffset;
        this.deliveryTopic = deliveryTopic;
        this.payload = payload;
        this.status = "PENDING";
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getDeliveryId() { return deliveryId; }
    public String getTenantId() { return tenantId; }
    public String getSourceEventId() { return sourceEventId; }
    public String getRoutingVersion() { return routingVersion; }
    public String getPlanVersion() { return planVersion; }
    public String getRouteKind() { return routeKind; }
    public String getRouteDimension() { return routeDimension; }
    public String getRouteValue() { return routeValue; }
    public String getRoutingKey() { return routingKey; }
    public String getSourceTopic() { return sourceTopic; }
    public int getSourcePartition() { return sourcePartition; }
    public long getSourceOffset() { return sourceOffset; }
    public String getDeliveryTopic() { return deliveryTopic; }
    public Integer getDeliveryPartition() { return deliveryPartition; }
    public void setDeliveryPartition(Integer deliveryPartition) { this.deliveryPartition = deliveryPartition; }
    public Long getDeliveryOffset() { return deliveryOffset; }
    public void setDeliveryOffset(Long deliveryOffset) { this.deliveryOffset = deliveryOffset; }
    public String getPayload() { return payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
