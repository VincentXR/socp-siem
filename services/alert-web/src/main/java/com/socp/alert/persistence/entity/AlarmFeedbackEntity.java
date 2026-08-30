package com.socp.alert.persistence.entity;

import com.socp.platform.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Durable, tenant-scoped analyst feedback attached to an alarm. */
@Entity
@Table(name = "t_alarm_feedback", uniqueConstraints = @UniqueConstraint(
        name = "uq_alarm_feedback_tenant_alarm_kind",
        columnNames = {"tenant_id", "alarm_id", "kind"}))
public class AlarmFeedbackEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "alarm_id", nullable = false, length = 255)
    private String alarmId;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(nullable = false, length = 4096)
    private String reason;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(length = 128)
    private String actor;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAlarmId() {
        return alarmId;
    }

    public void setAlarmId(String alarmId) {
        this.alarmId = alarmId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }
}
