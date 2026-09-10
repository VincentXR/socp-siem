-- A single Kafka offset cannot describe the progress of a shard that receives
-- records from more than one partition.  Keep a compact partition -> offset
-- vector with each snapshot.  Existing rows are timestamp-compatible legacy
-- checkpoints and intentionally start with an empty vector.
ALTER TABLE t_detection_state_snapshot
    ADD COLUMN IF NOT EXISTS partition_offsets_json TEXT NOT NULL DEFAULT '{}';

UPDATE t_detection_state_snapshot
SET partition_offsets_json = '{}'
WHERE partition_offsets_json IS NULL;
