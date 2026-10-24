-- Bound predecessor probes to the active state of one tenant/routing key.
-- The previous key/sequence index also scanned every published predecessor.
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_key_state_sequence
    ON t_ingestion_outbox (tenant_id, routing_key, status, sequence_no);
