ALTER TABLE t_alarm ADD COLUMN IF NOT EXISTS trigger_ingested_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_alarm ADD COLUMN IF NOT EXISTS alert_created_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_alarm ADD COLUMN IF NOT EXISTS processing_latency_ms BIGINT;
ALTER TABLE t_alarm ADD COLUMN IF NOT EXISTS trigger_event_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_t_alarm_alert_created_at ON t_alarm (tenant_id, alert_created_at);
