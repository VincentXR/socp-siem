-- Replace the timestamp-only path: generic plans otherwise filter all retained
-- timestamps when the requested status has no rows.
CREATE INDEX idx_detection_alert_outbox_status_updated
    ON t_detection_alert_outbox (status, updated_at);
DROP INDEX idx_detection_alert_outbox_updated;
ALTER INDEX idx_detection_alert_outbox_status_updated RENAME TO idx_detection_alert_outbox_updated;

CREATE INDEX idx_detection_alert_outbox_status_created
    ON t_detection_alert_outbox (status, created_at);
