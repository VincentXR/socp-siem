-- Existing receipts remain completed. New rows are admitted before external I/O.
ALTER TABLE t_notification_delivery ALTER COLUMN delivered_at DROP NOT NULL;
ALTER TABLE t_notification_delivery ADD COLUMN claim_token VARCHAR(36);
ALTER TABLE t_notification_delivery ADD COLUMN next_attempt_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_channel ALTER COLUMN target TYPE VARCHAR(2048);

CREATE TABLE t_notification_channel_namespace (
    tenant_id VARCHAR(64) PRIMARY KEY
);
