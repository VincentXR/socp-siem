package com.socp.detect.web.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Durable hand-off between Detection and Alert Web.
 *
 * <p>The alert id is the primary key because Detection emits deterministic
 * alert identities.  A replay therefore becomes an idempotent lookup instead
 * of another alert row.  The two delivery stages are represented by status:
 * PENDING means Alert Web has not acknowledged the payload, DELIVERED means
 * it has acknowledged it but the optional detect-model event is still due,
 * and PUBLISHED means both stages have completed.</p>
 */
@Entity
@Table(name = "t_detection_alert_outbox", indexes = {
        @Index(name = "idx_detection_alert_outbox_due", columnList = "status,next_attempt_at,created_at"),
        @Index(name = "idx_detection_alert_outbox_updated", columnList = "updated_at")
})
public class DetectionAlertOutboxEntity {

    @Id
    @Column(name = "alert_id", length = 255)
    private String alertId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** PENDING / PROCESSING / DELIVERED / PUBLISHED. */
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

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    protected DetectionAlertOutboxEntity() {
    }

    public DetectionAlertOutboxEntity(String alertId, String tenantId, String payload, Instant now) {
        this.alertId = alertId;
        this.tenantId = tenantId;
        this.payload = payload;
        this.status = "PENDING";
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getAlertId() {
        return alertId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public boolean alertDelivered() {
        return deliveredAt != null;
    }
}
