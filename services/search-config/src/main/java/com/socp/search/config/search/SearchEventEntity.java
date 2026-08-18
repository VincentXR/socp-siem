package com.socp.search.config.search;

import com.socp.platform.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 检索事件持久化实体（对应架构 §GLSP6 的 search 库 t_search_event）。
 * fields（归一化字段 Map）以 JSON 文本列存储。领域模型仍是 {@link SearchEvent} record。
 *
 * 继承 BaseEntity 获得 tenant_id/created_at/updated_at；本类的 timestamp 是事件发生时间
 * （日志自带），与基类的入库时间 created_at 语义不同，两者并存不冲突。
 */
@Entity
@Table(name = "t_search_event")
public class SearchEventEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    /** Pipeline-stable event identifier used to link detections back to source events. */
    @Column(name = "event_id", length = 255)
    private String eventId;
    private Instant timestamp;
    private String source;
    private String host;
    private String severity;
    @Column(length = 2000)
    private String msg;
    @Column(name = "fields_json", length = 4000)
    private String fieldsJson;
    @Column(name = "ecs_json", length = 4000)
    private String ecsJson;

    public SearchEventEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getFieldsJson() {
        return fieldsJson;
    }

    public void setFieldsJson(String fieldsJson) {
        this.fieldsJson = fieldsJson;
    }

    public String getEcsJson() {
        return ecsJson;
    }

    public void setEcsJson(String ecsJson) {
        this.ecsJson = ecsJson;
    }
}
