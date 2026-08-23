ALTER TABLE t_audit ADD COLUMN IF NOT EXISTS event_id VARCHAR(128);

UPDATE t_audit
SET event_id = CONCAT('legacy-', id)
WHERE event_id IS NULL OR event_id = '';

ALTER TABLE t_audit ALTER COLUMN event_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_t_audit_event_id ON t_audit (event_id);
