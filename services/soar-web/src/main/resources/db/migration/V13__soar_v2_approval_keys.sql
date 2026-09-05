-- Bind human approvals to a concrete gate instead of making a run-wide singleton.
-- Existing V7 rows are preserved as pre-dispatch approvals.
ALTER TABLE t_soar_approval ADD COLUMN IF NOT EXISTS approval_key VARCHAR(255);
UPDATE t_soar_approval SET approval_key = run_id
    WHERE approval_key IS NULL OR approval_key = '';
ALTER TABLE t_soar_approval ALTER COLUMN approval_key SET NOT NULL;
ALTER TABLE t_soar_approval DROP CONSTRAINT IF EXISTS uq_soar_approval_run;
ALTER TABLE t_soar_approval ADD CONSTRAINT uq_soar_approval_key
    UNIQUE (tenant_id, approval_key);
ALTER TABLE t_soar_approval ADD COLUMN IF NOT EXISTS node_run_id VARCHAR(64);
ALTER TABLE t_soar_approval ADD COLUMN IF NOT EXISTS action_ref VARCHAR(255);
ALTER TABLE t_soar_approval ADD COLUMN IF NOT EXISTS input_hash VARCHAR(128);
ALTER TABLE t_soar_approval ADD COLUMN IF NOT EXISTS target_snapshot_json TEXT;
ALTER TABLE t_soar_approval ADD COLUMN IF NOT EXISTS required_approvals INTEGER NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_soar_approval_gate
    ON t_soar_approval (tenant_id, run_id, approval_key, status);
