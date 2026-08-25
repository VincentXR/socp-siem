package com.socp.asset.web.persistence.entity;



import com.socp.asset.web.persistence.store.*;
import com.socp.asset.web.persistence.repository.*;
import com.socp.asset.web.persistence.entity.*;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 资产持久化实体（H2/PG，Flyway 建表）。 */
@Entity
@Table(name = "t_asset")
public class AssetEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(length = 64)
    private String ip;

    @Column(length = 64)
    private String os;

    @Column(length = 64)
    private String owner;

    @Column(length = 16)
    private String criticality;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 64)
    private String tenantId;

    public AssetEntity() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getCriticality() { return criticality; }
    public void setCriticality(String criticality) { this.criticality = criticality; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
}
