package com.socp.detect.web.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Durable lease and fencing token for one input-topic/partition/state-shard unit. */
@Entity
@Table(name = "t_detection_state_owner", indexes = {
        @Index(name = "idx_detection_state_owner_lease", columnList = "lease_until")
})
public class DetectionStateOwnerEntity {

    @Id
    @Column(name = "owner_key", length = 512)
    private String ownerKey;

    @Column(name = "input_topic", nullable = false, length = 255)
    private String inputTopic;

    @Column(name = "input_partition", nullable = false)
    private int inputPartition;

    @Column(name = "state_shard", nullable = false)
    private int stateShard;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(name = "fencing_epoch", nullable = false)
    private long fencingEpoch;

    @Column(name = "lease_until", nullable = false)
    private Instant leaseUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DetectionStateOwnerEntity() {
    }

    public DetectionStateOwnerEntity(String ownerKey, String inputTopic, int inputPartition,
                                     int stateShard, String ownerId, long fencingEpoch,
                                     Instant leaseUntil, Instant updatedAt) {
        this.ownerKey = ownerKey;
        this.inputTopic = inputTopic;
        this.inputPartition = inputPartition;
        this.stateShard = stateShard;
        this.ownerId = ownerId;
        this.fencingEpoch = fencingEpoch;
        this.leaseUntil = leaseUntil;
        this.updatedAt = updatedAt;
    }

    public String getOwnerKey() { return ownerKey; }
    public String getInputTopic() { return inputTopic; }
    public int getInputPartition() { return inputPartition; }
    public int getStateShard() { return stateShard; }
    public String getOwnerId() { return ownerId; }
    public long getFencingEpoch() { return fencingEpoch; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getUpdatedAt() { return updatedAt; }
}
