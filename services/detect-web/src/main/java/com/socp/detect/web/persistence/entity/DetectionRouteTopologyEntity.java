package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Durable tenant routing-topology pin for one delivery routing version. */
@Entity
@Table(name = "t_detection_route_topology", uniqueConstraints = @UniqueConstraint(
        name = "uk_detection_route_topology_tenant_version",
        columnNames = {"tenant_id", "routing_version"}))
public class DetectionRouteTopologyEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "routing_version", nullable = false, length = 64)
    private String routingVersion;

    @Column(name = "plan_version", nullable = false, length = 64)
    private String planVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DetectionRouteTopologyEntity() {
    }

    public DetectionRouteTopologyEntity(String tenantId, String routingVersion,
                                        String planVersion, Instant createdAt) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
        if (routingVersion == null || routingVersion.isBlank()) throw new IllegalArgumentException("routingVersion is required");
        if (planVersion == null || planVersion.isBlank()) throw new IllegalArgumentException("planVersion is required");
        this.id = UUID.nameUUIDFromBytes((tenantId + "\u0000" + routingVersion)
                .getBytes(StandardCharsets.UTF_8)).toString();
        this.tenantId = tenantId;
        this.routingVersion = routingVersion;
        this.planVersion = planVersion;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRoutingVersion() { return routingVersion; }
    public String getPlanVersion() { return planVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
