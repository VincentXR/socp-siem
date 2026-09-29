ALTER TABLE t_notification_delivery
    ADD COLUMN IF NOT EXISTS recovery_generation INTEGER NOT NULL DEFAULT 0;
