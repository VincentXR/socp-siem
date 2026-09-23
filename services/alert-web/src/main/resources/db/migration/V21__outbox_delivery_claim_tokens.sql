-- Old publishers must be drained before activating token-fenced writers.
ALTER TABLE outbox_event ADD COLUMN claim_token VARCHAR(36);
ALTER TABLE alarm_delivery ADD COLUMN claim_token VARCHAR(36);
