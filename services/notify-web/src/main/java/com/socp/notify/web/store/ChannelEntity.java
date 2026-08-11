package com.socp.notify.web.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 通知渠道持久化实体（t_channel）。 */
@Entity
@Table(name = "t_channel")
public class ChannelEntity {
    @Id @Column(length = 64) private String id;
    @Column(nullable = false, length = 128) private String name;
    @Column(nullable = false, length = 32) private String type;
    @Column(length = 512) private String target;
    @Column(nullable = false) private boolean enabled;
    @Column(length = 512) private String description;

    public ChannelEntity() {}
    public ChannelEntity(String id, String name, String type, String target, boolean enabled, String description) {
        this.id = id; this.name = name; this.type = type; this.target = target;
        this.enabled = enabled; this.description = description;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getTarget() { return target; }
    public boolean isEnabled() { return enabled; }
    public String getDescription() { return description; }
}
