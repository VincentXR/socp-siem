package com.socp.soar.web.persistence.entity;

import jakarta.persistence.Column;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.Transient;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "t_soar_automation_rule")
public class SoarAutomationRuleEntity implements Persistable<String> {
    @Id @Column(length = 64) private String id;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(nullable = false, length = 128) private String name;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private int priority;
    @Column(name = "trigger_type", nullable = false, length = 64) private String triggerType;
    @Column(name = "condition_json", nullable = false, columnDefinition = "TEXT") private String conditionJson;
    @Column(name = "actions_json", nullable = false, columnDefinition = "TEXT") private String actionsJson;
    @Column(name = "suppression_json", columnDefinition = "TEXT") private String suppressionJson;
    @Column(nullable = false) private int revision;
    @Column(name = "dedup_window_seconds") private Long dedupWindowSeconds;
    @Column(name = "cooldown_seconds") private Long cooldownSeconds;
    @Column(name = "group_by", length = 512) private String groupBy;
    @Column(name = "max_concurrent_runs") private Integer maxConcurrentRuns;
    @Column(name = "conflict_strategy", length = 24) private String conflictStrategy;
    @Column(name = "valid_from") private Instant validFrom;
    @Column(name = "valid_until") private Instant validUntil;
    @Column(name = "created_by", nullable = false, length = 128) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private Long rowVersion;

    public SoarAutomationRuleEntity() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getConditionJson() { return conditionJson; }
    public void setConditionJson(String conditionJson) { this.conditionJson = conditionJson; }
    public String getActionsJson() { return actionsJson; }
    public void setActionsJson(String actionsJson) { this.actionsJson = actionsJson; }
    public String getSuppressionJson() { return suppressionJson; }
    public void setSuppressionJson(String suppressionJson) { this.suppressionJson = suppressionJson; }
    public int getRevision() { return revision; }
    public void setRevision(int revision) { this.revision = revision; }
    public Long getDedupWindowSeconds() { return dedupWindowSeconds; }
    public void setDedupWindowSeconds(Long dedupWindowSeconds) { this.dedupWindowSeconds = dedupWindowSeconds; }
    public Long getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(Long cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    public String getGroupBy() { return groupBy; }
    public void setGroupBy(String groupBy) { this.groupBy = groupBy; }
    public Integer getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(Integer maxConcurrentRuns) { this.maxConcurrentRuns = maxConcurrentRuns; }
    public String getConflictStrategy() { return conflictStrategy; }
    public void setConflictStrategy(String conflictStrategy) { this.conflictStrategy = conflictStrategy; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant validUntil) { this.validUntil = validUntil; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getRowVersion() { return rowVersion; }
    public void setRowVersion(Long rowVersion) { this.rowVersion = rowVersion; }
    @Override
    @Transient
    public boolean isNew() {
        return rowVersion == null;
    }

}
