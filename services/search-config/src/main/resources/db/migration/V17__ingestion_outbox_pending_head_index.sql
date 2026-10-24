-- Filter the active state before grouping routing keys. The same equality
-- prefix also serves the claim-time predecessor and active-owner probes.
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_state_key_sequence
    ON t_ingestion_outbox (status, tenant_id, routing_key, sequence_no);

-- V16's equivalent equality-probe index is superseded by the state-first one.
DROP INDEX IF EXISTS idx_ingestion_outbox_key_state_sequence;
