-- Allow more than one approval/manual/unknown signal for the same durable run.
-- V10 used (tenant, run, signal_type) as a singleton, which could overwrite a
-- sibling gate when a graph fan-out produced two signals before the worker ran.
ALTER TABLE t_soar_signal_outbox ADD COLUMN IF NOT EXISTS signal_key VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE t_soar_signal_outbox DROP CONSTRAINT IF EXISTS uq_soar_signal_outbox_business;
ALTER TABLE t_soar_signal_outbox ADD CONSTRAINT uq_soar_signal_outbox_business
    UNIQUE (tenant_id, run_id, signal_type, signal_key);
CREATE INDEX IF NOT EXISTS idx_soar_signal_outbox_gate
    ON t_soar_signal_outbox (tenant_id, run_id, signal_type, signal_key, status);
