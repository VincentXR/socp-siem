-- Preserve the raw-payload-free DetectionResult envelope at the Alert
-- boundary.  Evidence remains in t_alarm_evidence; this column is only for
-- rule/version/position/suppression lineage and is safe for list/detail APIs.
ALTER TABLE t_alarm ADD COLUMN IF NOT EXISTS detection_result_json TEXT;

CREATE INDEX IF NOT EXISTS idx_t_alarm_tenant_trigger_event
    ON t_alarm (tenant_id, trigger_event_id);
