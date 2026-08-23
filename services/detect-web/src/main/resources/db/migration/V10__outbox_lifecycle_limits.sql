-- Completed durable hand-offs may be purged after their configured retention.
-- DEAD rows are deliberately retained for investigation and explicit replay.
CREATE INDEX IF NOT EXISTS idx_detection_alert_outbox_published_retention
    ON t_detection_alert_outbox (status, published_at);
CREATE INDEX IF NOT EXISTS idx_rule_change_outbox_published_retention
    ON t_rule_change_outbox (status, published_at);
