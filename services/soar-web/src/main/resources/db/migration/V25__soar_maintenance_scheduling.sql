-- Maintenance retry hints are independent of workflow progress and row_version.
ALTER TABLE t_soar_run ADD COLUMN cancel_next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE t_soar_run ADD COLUMN recovery_next_check_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX idx_soar_run_cancel_due ON t_soar_run(status, cancel_next_attempt_at, id);
CREATE INDEX idx_soar_run_recovery_due ON t_soar_run(status, recovery_next_check_at, updated_at, id);
