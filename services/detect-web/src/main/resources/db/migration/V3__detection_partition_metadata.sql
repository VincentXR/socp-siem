-- Partition ownership metadata makes replay selective after a consumer
-- rebalance. Existing HTTP/test journal rows intentionally keep NULL values.
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS kafka_partition INTEGER;
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS kafka_offset BIGINT;
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS routing_key VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_detection_event_partition_occurred
    ON t_detection_event (kafka_partition, occurred_at);
