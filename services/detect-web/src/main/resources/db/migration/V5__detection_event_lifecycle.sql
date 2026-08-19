-- The journal claim is replayable until the durable Detection result is
-- committed. Legacy rows predate the lifecycle and are treated as completed
-- accepted events so an upgrade does not unexpectedly re-run old traffic.
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS status VARCHAR(16);
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS status_reason VARCHAR(1024);
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS dead_lettered_at TIMESTAMP(6) WITH TIME ZONE;

UPDATE t_detection_event SET status = 'COMPLETED' WHERE status IS NULL;
ALTER TABLE t_detection_event ALTER COLUMN status SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_detection_event_status_occurred
    ON t_detection_event (status, occurred_at);
CREATE INDEX IF NOT EXISTS idx_detection_event_status_partition_offset
    ON t_detection_event (status, kafka_partition, kafka_offset);
