-- Persist the remote-side operation time on action attempts when a
-- connector can supply one (e.g. a Date header or vendor operation time).
-- NULL means no trustworthy remote timestamp was available; attempt.created_at
-- and completed_at remain the local record of the attempt lifecycle, so this
-- column must never be treated as the attempt's own completion clock.
ALTER TABLE t_soar_action_attempt ADD COLUMN IF NOT EXISTS remote_time TIMESTAMP(6) WITH TIME ZONE;
