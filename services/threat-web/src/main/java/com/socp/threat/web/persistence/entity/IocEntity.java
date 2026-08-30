package com.socp.threat.web.persistence.entity;


import com.socp.platform.data.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 威胁情报 IOC 持久化实体（对应架构 §TIM-2 的 threat 库 t_ioc）。
 * tags 以 JSON 文本列存储。领域模型仍是 {@link com.socp.threat.web.domain.Ioc} record。
 *
 * 继承 BaseEntity 获得 tenant_id/created_at/updated_at 三列，@PrePersist 会从 TenantContext
 * 自动灌租户（SDK 级隔离，见 §3.3）。本类自有的 firstSeen/lastSeen 是情报语义的时间，
 * 与基类的审计时间不冲突，故可安全继承。
 */
@Entity
@Table(name = "t_ioc")
public class IocEntity extends BaseEntity {
    @Id
    private String id;
    private String type;
    /** 列名不能直接叫 value：H2 2.x / SQL:2016 里 VALUE 是保留字，建表会语法报错 */
    @Column(name = "ioc_value")
    private String value;
    private String severity;
    private String source;
    @Column(length = 1024)
    private String description;
    @Column(name = "tags", length = 1024)
    private String tagsJson;
    private Instant firstSeen;
    private Instant lastSeen;
    @Column(name = "feed", length = 256)
    private String feed;
    @Column(name = "external_id", length = 512)
    private String externalId;
    private Double confidence;
    @Column(name = "tlp", length = 32)
    private String tlp;
    private Instant validFrom;
    private Instant validUntil;
    private Instant expiration;
    private boolean revoked;
    @Column(length = 128)
    private String provenance;

    public IocEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(Instant firstSeen) {
        this.firstSeen = firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public String getFeed() { return feed; }
    public void setFeed(String feed) { this.feed = feed; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getTlp() { return tlp; }
    public void setTlp(String tlp) { this.tlp = tlp; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public Instant getExpiration() { return expiration; }
    public void setExpiration(Instant expiration) { this.expiration = expiration; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public String getProvenance() { return provenance; }
    public void setProvenance(String provenance) { this.provenance = provenance; }
}
