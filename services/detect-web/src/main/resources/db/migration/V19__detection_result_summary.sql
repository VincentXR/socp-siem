-- Keep a compact calculation/suppression explanation with every completed
-- journal row, including events that produced no alert outbox row.
ALTER TABLE t_detection_event
    ADD COLUMN IF NOT EXISTS result_json TEXT;

UPDATE t_detection_event
SET result_json = '{}'
WHERE result_json IS NULL;
