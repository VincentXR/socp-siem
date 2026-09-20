package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Durable receipt for one immutable canonical Kafka source position. */
@Entity
@Table(name = "t_detection_route_source", uniqueConstraints = @UniqueConstraint(
        name = "uk_detection_route_source_position",
        columnNames = {"source_topic", "source_partition", "source_offset"}), indexes = {
        @Index(name = "idx_detection_route_source_event",
                columnList = "tenant_id,source_event_id")
})
public class DetectionRouteSourceEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "source_topic", nullable = false, length = 255)
    private String sourceTopic;

    @Column(name = "source_partition", nullable = false)
    private int sourcePartition;

    @Column(name = "source_offset", nullable = false)
    private long sourceOffset;

    @Column(name = "source_event_id", nullable = false, length = 255)
    private String sourceEventId;

    @Column(name = "routing_version", nullable = false, length = 64)
    private String routingVersion;

    @Column(name = "plan_version", nullable = false, length = 64)
    private String planVersion;

    @Column(name = "delivery_count", nullable = false)
    private int deliveryCount;

    @Column(name = "missing_dimensions", length = 2048)
    private String missingDimensions;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "status_reason", length = 1024)
    private String statusReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DetectionRouteSourceEntity() {
    }

    public DetectionRouteSourceEntity(String tenantId, String sourceTopic,
                                      int sourcePartition, long sourceOffset,
                                      String sourceEventId, String routingVersion,
                                      String planVersion, int deliveryCount,
                                      String missingDimensions, String status,
                                      String statusReason, Instant createdAt) {
        String tuple = sourceTopic + "\u0000" + sourcePartition + "\u0000" + sourceOffset;
        this.id = UUID.nameUUIDFromBytes(tuple.getBytes(StandardCharsets.UTF_8)).toString();
        this.tenantId = tenantId;
        this.sourceTopic = sourceTopic;
        this.sourcePartition = sourcePartition;
        this.sourceOffset = sourceOffset;
        this.sourceEventId = sourceEventId;
        this.routingVersion = routingVersion;
        this.planVersion = planVersion;
        this.deliveryCount = Math.max(0, deliveryCount);
        this.missingDimensions = missingDimensions;
        this.status = status;
        this.statusReason = statusReason;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSourceTopic() { return sourceTopic; }
    public int getSourcePartition() { return sourcePartition; }
    public long getSourceOffset() { return sourceOffset; }
    public String getSourceEventId() { return sourceEventId; }
    public String getRoutingVersion() { return routingVersion; }
    public String getPlanVersion() { return planVersion; }
    public int getDeliveryCount() { return deliveryCount; }
    public String getMissingDimensions() { return missingDimensions; }
    public String getStatus() { return status; }
    public String getStatusReason() { return statusReason; }
    public Instant getCreatedAt() { return createdAt; }
}
