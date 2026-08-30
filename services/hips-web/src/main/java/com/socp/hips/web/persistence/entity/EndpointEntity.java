package com.socp.hips.web.persistence.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 端点注册表持久化实体（t_endpoint）。 */
@Entity
@Table(name = "t_endpoint")
public class EndpointEntity {
    @Id @Column(name = "id", length = 64) private String storageId;
    @Column(name = "endpoint_id", nullable = false, length = 64) private String endpointId;
    @Column(name = "tenant_id", nullable = false, length = 64) private String tenantId;
    @Column(nullable = false, length = 128) private String hostname;
    @Column(length = 64) private String ip;
    @Column(length = 64) private String os;
    @Column(name = "agent_version", length = 64) private String agentVersion;
    @Column(length = 32) private String status;
    @Column(name = "last_heartbeat") private Instant lastHeartbeat;

    public EndpointEntity() {}
    public EndpointEntity(String storageId, String endpointId, String tenantId, String hostname, String ip,
                          String os, String agentVersion, String status, Instant lastHeartbeat) {
        this.storageId = storageId; this.endpointId = endpointId; this.tenantId = tenantId;
        this.hostname = hostname; this.ip = ip; this.os = os;
        this.agentVersion = agentVersion; this.status = status; this.lastHeartbeat = lastHeartbeat;
    }
    public String getStorageId() { return storageId; }
    public String getEndpointId() { return endpointId; }
    public String getTenantId() { return tenantId; }
    public String getHostname() { return hostname; }
    public String getIp() { return ip; }
    public String getOs() { return os; }
    public String getAgentVersion() { return agentVersion; }
    public String getStatus() { return status; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setStatus(String status) { this.status = status; }
    public void setLastHeartbeat(Instant t) { this.lastHeartbeat = t; }
}
