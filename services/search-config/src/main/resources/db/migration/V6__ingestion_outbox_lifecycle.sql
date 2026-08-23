-- Bounded retry lifecycle for canonical-event publication intents.
ALTER TABLE t_ingestion_outbox ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE t_ingestion_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_ingestion_outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(1024);

UPDATE t_ingestion_outbox
SET attempts = COALESCE(attempts, 0),
    next_attempt_at = COALESCE(next_attempt_at, published_at, created_at, CURRENT_TIMESTAMP)
WHERE next_attempt_at IS NULL;

ALTER TABLE t_ingestion_outbox ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_due_v2
    ON t_ingestion_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_published_retention
    ON t_ingestion_outbox (status, published_at);
