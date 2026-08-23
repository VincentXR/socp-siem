package com.socp.soc.store;

import com.socp.soc.model.TenantInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable tenant directory row. */
@Entity
@Table(name = "t_tenant")
class TenantEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "user_count", nullable = false)
    private int userCount;

    @Column(name = "alarm_count", nullable = false)
    private int alarmCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantEntity() {
    }

    TenantEntity(TenantInfo info) {
        update(info);
    }

    void update(TenantInfo info) {
        this.id = info.id();
        this.code = info.code();
        this.name = info.name();
        this.userCount = info.userCount();
        this.alarmCount = info.alarmCount();
        this.createdAt = info.createdAt();
    }

    TenantInfo toInfo() {
        return new TenantInfo(id, name, code, userCount, alarmCount, createdAt);
    }
}
