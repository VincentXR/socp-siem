-- V21 moved runtime positions to delivery_*; the old kafka_* columns remain
-- for historical compatibility, but new receipts no longer populate them.
CREATE INDEX idx_detection_event_delivery_replay
    ON t_detection_event (tenant_id, status, delivery_topic, delivery_partition, delivery_offset);

DROP INDEX idx_detection_event_checkpoint_replay;
ALTER INDEX idx_detection_event_delivery_replay RENAME TO idx_detection_event_checkpoint_replay;
DROP INDEX idx_detection_event_status_partition_offset;
DROP INDEX idx_detection_event_partition_occurred;
