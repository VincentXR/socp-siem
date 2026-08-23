package com.socp.search.config.store;

import com.socp.platform.tenant.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 日志源持久化实体（对应架构 §GLSP6 的 search 库 t_log_source）。
 * 枚举 type/format 存为字符串；列表型字段（parseRuleIds/tags）存 JSON 文本列。
 * 领域模型仍是 {@link com.socp.search.config.domain.LogSource} record。
 *
 * 【为什么不继承 BaseEntity】本类的 createdAt 由领域模型 LogSource 显式带入
 * （LogSourceStore 直接 setCreatedAt），与 BaseEntity 的同名字段会重复映射到 created_at 列
 * （Hibernate 报 Repeated column），且业务时间会被基类的 @PrePersist 覆盖。
 * 因此这里只复刻 BaseEntity 的租户注入逻辑。
 */
@Entity
@Table(name = "t_log_source")
public class LogSourceEntity {
    @Id
    @Column(name = "id")
    private String storageId;

    @Column(name = "source_id", nullable = false)
    private String sourceId;

    /** 多租户隔离列，落库前从 TenantContext 自动注入（等价 BaseEntity 的行为） */
    @Column(name = "tenant_id")
    private String tenantId;
    private String name;
    @Column(name = "src_type")
    private String type;
    private String format;
    private String path;
    private String address;
    private String topic;
    private String env;
    private boolean enabled;
    @Column(name = "read_from")
    private String readFrom;
    @Column(length = 2000)
    private String multiline;
    @Column(name = "sink_target_id")
    private String sinkTargetId;
    @Column(name = "parse_rule_ids", length = 2000)
    private String parseRuleIdsJson;
    @Column(length = 1024)
    private String description;
    private String protocol;
    private String charset;
    @Column(name = "time_field")
    private String timeField;
    private String timezone;
    @Column(name = "tags", length = 1024)
    private String tagsJson;
    private Integer frequency;
    @Column(name = "category_id")
    private String categoryId;
    @Column(name = "group_id")
    private String groupId;
    private Instant createdAt;

    public LogSourceEntity() {
    }

    @PrePersist
    void onCreate() {
        if (tenantId == null) {
            tenantId = TenantContext.get();
        }
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getId() {
        return sourceId;
    }

    public void setId(String id) {
        this.sourceId = id;
    }

    public String getStorageId() {
        return storageId;
    }

    public void setStorageId(String storageId) {
        this.storageId = storageId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getReadFrom() {
        return readFrom;
    }

    public void setReadFrom(String readFrom) {
        this.readFrom = readFrom;
    }

    public String getMultiline() {
        return multiline;
    }

    public void setMultiline(String multiline) {
        this.multiline = multiline;
    }

    public String getSinkTargetId() {
        return sinkTargetId;
    }

    public void setSinkTargetId(String sinkTargetId) {
        this.sinkTargetId = sinkTargetId;
    }

    public String getParseRuleIdsJson() {
        return parseRuleIdsJson;
    }

    public void setParseRuleIdsJson(String parseRuleIdsJson) {
        this.parseRuleIdsJson = parseRuleIdsJson;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getTimeField() {
        return timeField;
    }

    public void setTimeField(String timeField) {
        this.timeField = timeField;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public Integer getFrequency() {
        return frequency;
    }

    public void setFrequency(Integer frequency) {
        this.frequency = frequency;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
