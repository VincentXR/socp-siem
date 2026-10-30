-- Stop head discovery after the first eligible keys in delivery order.
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_due_sequence
    ON t_ingestion_outbox (status, next_attempt_at, sequence_no, id);

-- Cover first-pending and bounded member lookups, including deterministic
-- identity ordering for legacy rows with equal sequence values.
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_state_key_order
    ON t_ingestion_outbox (status, tenant_id, routing_key, sequence_no, id, next_attempt_at);

-- These prefixes retain the earlier due and state/key lookup capabilities.
DROP INDEX IF EXISTS idx_ingestion_outbox_due_v2;
DROP INDEX IF EXISTS idx_ingestion_outbox_state_key_sequence;
