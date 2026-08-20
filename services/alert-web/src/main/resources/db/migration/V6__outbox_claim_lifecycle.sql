ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP(6) WITH TIME ZONE;

UPDATE outbox_event
SET updated_at = COALESCE(published_at, created_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;

ALTER TABLE outbox_event ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_status_updated
    ON outbox_event (status, updated_at);
