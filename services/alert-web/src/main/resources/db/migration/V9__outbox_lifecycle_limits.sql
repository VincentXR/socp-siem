-- Bounded, inspectable retry lifecycle for both durable alert outboxes.
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS last_error VARCHAR(1024);

UPDATE outbox_event
SET attempts = COALESCE(attempts, 0),
    next_attempt_at = COALESCE(next_attempt_at, published_at, created_at, CURRENT_TIMESTAMP)
WHERE next_attempt_at IS NULL;

ALTER TABLE outbox_event ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_due
    ON outbox_event (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_published_retention
    ON outbox_event (status, published_at);
CREATE INDEX IF NOT EXISTS idx_alarm_delivery_delivered_retention
    ON alarm_delivery (status, delivered_at);
