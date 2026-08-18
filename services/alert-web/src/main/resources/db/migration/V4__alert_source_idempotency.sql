-- Stable Detection alert ids prevent duplicate t_alarm rows after replay.
ALTER TABLE t_alarm ADD COLUMN IF NOT EXISTS source_alert_id VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS uq_alarm_tenant_source_alert
    ON t_alarm (tenant_id, source_alert_id);
