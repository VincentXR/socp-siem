package com.socp.alert.persistence.entity;


import com.socp.platform.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 告警处置持久化实体（P3，2026-08-12）：assignee/status/notes 落库，
 * 替代原纯内存 ConcurrentHashMap（重启不丢，保留处置历史）。
 */
@Entity
@Table(name = "t_alarm_disposition")
public class DispositionEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "alarm_id")
    private String alarmId;

    private String assignee;

    private String status;

    /** 备注列表（JSON 数组：[{author, content, at}]） */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Idempotent disposition tags stored as a JSON string array. */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** Stable keys for connector-originated notes; bounded by the service. */
    @Column(name = "note_keys", columnDefinition = "TEXT")
    private String noteKeys;

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

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getNoteKeys() {
        return noteKeys;
    }

    public void setNoteKeys(String noteKeys) {
        this.noteKeys = noteKeys;
    }
}
