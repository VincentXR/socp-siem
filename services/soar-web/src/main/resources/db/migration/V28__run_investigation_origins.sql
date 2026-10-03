ALTER TABLE t_soar_run ADD COLUMN origin_alarm_id VARCHAR(255);
ALTER TABLE t_soar_run ADD COLUMN origin_case_id VARCHAR(255);
UPDATE t_soar_run SET origin_alarm_id = subject_id WHERE LOWER(subject_type) IN ('alert', 'alarm', 'alert.created', 'alarm.created');
UPDATE t_soar_run SET origin_case_id = subject_id WHERE LOWER(subject_type) IN ('case', 'incident', 'incident.created', 'case.created');
CREATE INDEX idx_soar_run_origin_alarm ON t_soar_run (tenant_id, origin_alarm_id, created_at, id);
CREATE INDEX idx_soar_run_origin_case ON t_soar_run (tenant_id, origin_case_id, created_at, id);
