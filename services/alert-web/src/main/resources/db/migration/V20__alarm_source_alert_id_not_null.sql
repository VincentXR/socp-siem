-- t_alarm.source_alert_id is the alert idempotency key. PostgreSQL (and H2 in
-- MODE=PostgreSQL) treat NULLs as distinct, so the (tenant_id, source_alert_id)
-- unique index created by V4 never constrains rows whose key is NULL, and those
-- rows cannot be de-duplicated on replay. Normalize the pre-existing NULL rows
-- to a stable per-row identity, then make the column NOT NULL so the uniqueness
-- contract actually holds. This mirrors the SET NOT NULL fail-closed pattern
-- already used by V7 for t_alarm_disposition.tenant_id.
--
-- Compatibility window: existing keyless alarms keep a deterministic identity of
-- the form 'legacy:<id>' and are never merged. New writes must carry a
-- source_alert_id; AlarmService derives a value when a keyless producer omits
-- one, so no insert can violate the constraint. See docs/idempotency-contract.md.
UPDATE t_alarm SET source_alert_id = 'legacy:' || id
    WHERE source_alert_id IS NULL;
ALTER TABLE t_alarm ALTER COLUMN source_alert_id SET NOT NULL;
