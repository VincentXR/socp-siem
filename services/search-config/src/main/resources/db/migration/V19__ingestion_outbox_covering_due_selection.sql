-- Cover tenant/key head checks without losing due-time delivery order.
-- With stale empty-queue statistics, a non-covering due index can lose to
-- the state/key index plus a sort of the complete pending population.
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_due_covering
    ON t_ingestion_outbox (status, next_attempt_at, sequence_no, id, tenant_id, routing_key);

DROP INDEX IF EXISTS idx_ingestion_outbox_due_sequence;
