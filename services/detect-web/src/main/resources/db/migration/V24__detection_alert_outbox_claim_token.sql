-- A retry count is not an owner fence: operator requeue resets it to zero.
-- Legacy in-flight claims remain nullable and recover through lease expiry.
ALTER TABLE t_detection_alert_outbox ADD COLUMN claim_token VARCHAR(36);
