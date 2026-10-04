package com.socp.alert.persistence.entity;

import com.socp.platform.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 告警静默窗口（t_alarm_suppression）：分析师确认的误报按检测范围落库，
 * 后续命中同范围的告警在创建时就带 SUPPRESSED 状态。
 *
 * <p>它存在的理由是跨副本正确性：检测侧的 {@code Suppressor} 是进程内存态，
 * 重启即清零、多实例不共享，不能承载人工确认的降噪决策。
 */
@Entity
@Table(name = "t_alarm_suppression")
public class AlarmSuppressionEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    /** Empty string means "every entity of this rule"; NOT NULL keeps the unique key total. */
    @Column(name = "entity_key", nullable = false)
    private String entityKey;

    @Column(nullable = false, length = 32)
    private String origin;

    @Column(nullable = false, length = 4096)
    private String reason;

    @Column(name = "alarm_id")
    private String alarmId;

    @Column(length = 128)
    private String actor;

    @Column(name = "expires_at", nullable = false)
    private java.time.Instant expiresAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getEntityKey() {
        return entityKey;
    }

    public void setEntityKey(String entityKey) {
        this.entityKey = entityKey;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getAlarmId() {
        return alarmId;
    }

    public void setAlarmId(String alarmId) {
        this.alarmId = alarmId;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public java.time.Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(java.time.Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
