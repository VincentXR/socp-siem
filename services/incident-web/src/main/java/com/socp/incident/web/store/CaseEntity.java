package com.socp.incident.web.store;

import com.socp.platform.tenant.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 案件持久化实体（对应架构 §IR-4 的 case 库 t_case）。
 * 嵌套结构（ruleIds / alarmIds / timeline）以 JSON 文本列存储，避免在内存态切片里引入额外的关联表。
 * 领域模型仍是 {@link com.socp.incident.web.domain.Case} record，由 CaseStore 负责互转。
 *
 * 【为什么不继承 BaseEntity】本类的 createdAt/updatedAt 由领域模型 Case 显式带入
 * （CaseStore 直接 setCreatedAt/setUpdatedAt），而 BaseEntity 也声明了同名字段并在
 * @PrePersist 里覆写它们——继承会导致 created_at 被重复映射（Hibernate 报 Repeated column），
 * 且业务传入的时间会被基类冲掉。因此这里只复刻 BaseEntity 的租户注入逻辑。
 */
@Entity
@Table(name = "t_case")
public class CaseEntity {
    @Id
    private String id;

    /** 多租户隔离列，落库前从 TenantContext 自动注入（等价 BaseEntity 的行为） */
    @Column(name = "tenant_id")
    private String tenantId;
    private String title;
    private String entity;
    private String severity;
    private String status;
    @Column(name = "rule_ids", length = 4000)
    private String ruleIdsJson;
    @Column(name = "alarm_ids", length = 4000)
    private String alarmIdsJson;
    @Column(name = "timeline", length = 16000)
    private String timelineJson;
    private String assignee;
    private Instant createdAt;
    private Instant updatedAt;

    public CaseEntity() {
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
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getEntity() {
        return entity;
    }

    public void setEntity(String entity) {
        this.entity = entity;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRuleIdsJson() {
        return ruleIdsJson;
    }

    public void setRuleIdsJson(String ruleIdsJson) {
        this.ruleIdsJson = ruleIdsJson;
    }

    public String getAlarmIdsJson() {
        return alarmIdsJson;
    }

    public void setAlarmIdsJson(String alarmIdsJson) {
        this.alarmIdsJson = alarmIdsJson;
    }

    public String getTimelineJson() {
        return timelineJson;
    }

    public void setTimelineJson(String timelineJson) {
        this.timelineJson = timelineJson;
    }

    public String getAssignee() {
        return assignee;
    }

    public void setAssignee(String assignee) {
        this.assignee = assignee;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
