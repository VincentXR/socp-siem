CREATE TABLE IF NOT EXISTS t_incident_merge_lock (
    tenant_id VARCHAR(64) NOT NULL,
    shard_id  INTEGER NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_incident_merge_lock PRIMARY KEY (tenant_id, shard_id),
    CONSTRAINT ck_incident_merge_lock_shard CHECK (shard_id >= 0 AND shard_id < 256)
);

CREATE INDEX IF NOT EXISTS idx_incident_merge_lock_tenant
    ON t_incident_merge_lock (tenant_id, shard_id);
