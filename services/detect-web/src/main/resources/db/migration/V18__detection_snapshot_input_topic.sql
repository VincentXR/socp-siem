-- Bind a new state checkpoint to the input topic that produced its progress
-- vector. Existing rows remain nullable legacy checkpoints and are rewritten
-- with the configured topic on their next successful save.
ALTER TABLE t_detection_state_snapshot
    ADD COLUMN IF NOT EXISTS input_topic VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_detection_state_snapshot_topic
    ON t_detection_state_snapshot (input_topic, tenant_id, shard_id);
