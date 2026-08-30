package com.socp.soar.web.persistence.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 剧本持久化实体（H2/PG，Flyway 建表；actions 以 JSON 字符串存储）。 */
@Entity
@Table(name = "t_playbook")
public class PlaybookEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "trigger_desc", length = 256)
    private String trigger;

    /** actions JSON 数组，如 ["查询资产归属","下发防火墙封禁"] */
    @Column(length = 2048)
    private String actions;

    @Column(nullable = false)
    private boolean enabled;

    @Column(length = 16)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64, nullable = false)
    private String tenantId;

    public PlaybookEntity() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public String getActions() { return actions; }
    public void setActions(String actions) { this.actions = actions; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
