package com.socp.search.config.search;

import com.socp.platform.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Durable Kafka publication intent created with the local search event. */
@Entity
@Table(name = "t_ingestion_outbox")
public class IngestionOutboxEvent extends BaseEntity {

    @Id
    private String id;

    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;

    @Column(name = "routing_key", nullable = false, length = 512)
    private String routingKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "traceparent", length = 255)
    private String traceparent;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public IngestionOutboxEvent() {
    }

    static IngestionOutboxEvent pending(String eventId, String routingKey, String payload,
                                        String traceparent) {
        IngestionOutboxEvent event = new IngestionOutboxEvent();
        event.id = UUID.randomUUID().toString();
        event.eventId = eventId;
        event.routingKey = routingKey;
        event.payload = payload;
        event.traceparent = traceparent;
        event.status = "PENDING";
        return event;
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getTraceparent() {
        return traceparent;
    }

    public String getStatus() {
        return status;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
