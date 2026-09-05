-- Retention and janitor support indexes (G9).  All indexes are additive and
-- never change table semantics.
CREATE INDEX IF NOT EXISTS idx_soar_run_status_updated
    ON t_soar_run (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_soar_node_run_run_id
    ON t_soar_node_run (run_id);
CREATE INDEX IF NOT EXISTS idx_soar_node_run_tenant_idem
    ON t_soar_node_run (tenant_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_soar_action_attempt_node_run
    ON t_soar_action_attempt (node_run_id);
CREATE INDEX IF NOT EXISTS idx_soar_run_event_created
    ON t_soar_run_event (created_at);
CREATE INDEX IF NOT EXISTS idx_soar_approval_status_expires
    ON t_soar_approval (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_soar_manual_task_assignee
    ON t_soar_manual_task (tenant_id, status, assignee);
