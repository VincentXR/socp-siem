CREATE TABLE IF NOT EXISTS t_detection_state_snapshot (
    id                    VARCHAR(36) NOT NULL,
    tenant_id             VARCHAR(64) NOT NULL,
    rule_id               VARCHAR(128) NOT NULL,
    rule_version          VARCHAR(64) NOT NULL,
    shard_id              INTEGER NOT NULL,
    last_processed_offset BIGINT NOT NULL,
    serialized_state      TEXT NOT NULL,
    snapshot_timestamp    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_detection_state_snapshot PRIMARY KEY (id),
    CONSTRAINT uq_detection_state_snapshot_key UNIQUE (tenant_id, rule_id, shard_id)
);

CREATE INDEX IF NOT EXISTS idx_detection_state_snapshot_timestamp
    ON t_detection_state_snapshot (tenant_id, snapshot_timestamp);

CREATE INDEX IF NOT EXISTS idx_detection_event_completed_replay
    ON t_detection_event (tenant_id, status, completed_at);
