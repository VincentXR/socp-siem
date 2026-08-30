package com.socp.hips.web.persistence.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable HIPS endpoint event envelope; source fields remain JSON for compatibility. */
@Entity
@Table(name = "t_endpoint_event")
public class EndpointEventEntity {

    @Id
    @Column(length = 36)
    private String eventId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "hostname", length = 128)
    private String hostname;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    protected EndpointEventEntity() {
    }

    public EndpointEventEntity(String eventId, String tenantId, String hostname,
                               Instant receivedAt, String payloadJson) {
        this.eventId = eventId;
        this.tenantId = tenantId;
        this.hostname = hostname;
        this.receivedAt = receivedAt;
        this.payloadJson = payloadJson;
    }

    public String getEventId() { return eventId; }
    public String getTenantId() { return tenantId; }
    public String getHostname() { return hostname; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getPayloadJson() { return payloadJson; }
}
