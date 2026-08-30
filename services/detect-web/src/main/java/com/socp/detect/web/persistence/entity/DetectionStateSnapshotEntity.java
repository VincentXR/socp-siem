package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Durable rule-state checkpoint; bytes are stored as portable base64 text. */
@Entity
@Table(name = "t_detection_state_snapshot", uniqueConstraints = @UniqueConstraint(
        name = "uq_detection_state_snapshot_key",
        columnNames = {"tenant_id", "rule_id", "shard_id"}))
public class DetectionStateSnapshotEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "rule_id", nullable = false, length = 128)
    private String ruleId;

    @Column(name = "rule_version", nullable = false, length = 64)
    private String ruleVersion;

    @Column(name = "shard_id", nullable = false)
    private int shardId;

    @Column(name = "last_processed_offset", nullable = false)
    private long lastProcessedOffset;

    @Column(name = "serialized_state", nullable = false, columnDefinition = "TEXT")
    private String serializedState;

    @Column(name = "snapshot_timestamp", nullable = false)
    private Instant snapshotTimestamp;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public int getShardId() { return shardId; }
    public void setShardId(int shardId) { this.shardId = shardId; }
    public long getLastProcessedOffset() { return lastProcessedOffset; }
    public void setLastProcessedOffset(long lastProcessedOffset) { this.lastProcessedOffset = lastProcessedOffset; }
    public String getSerializedState() { return serializedState; }
    public void setSerializedState(String serializedState) { this.serializedState = serializedState; }
    public Instant getSnapshotTimestamp() { return snapshotTimestamp; }
    public void setSnapshotTimestamp(Instant snapshotTimestamp) { this.snapshotTimestamp = snapshotTimestamp; }
}
