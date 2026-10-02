-- Supports one latest retained run per playbook in a bounded catalog page.
CREATE INDEX IF NOT EXISTS idx_soar_run_playbook_latest
    ON t_soar_run (tenant_id, playbook_id, created_at DESC, id DESC);
