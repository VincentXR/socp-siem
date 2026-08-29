-- Every publication intent must carry its tenant across the Kafka boundary.
-- Do not silently assign legacy rows to the default tenant: unresolved rows
-- require an operator reconciliation before this migration can proceed.
UPDATE t_ingestion_outbox o
SET tenant_id = (
    SELECT e.tenant_id
    FROM t_search_event e
    WHERE e.event_id = o.event_id
       OR e.id = o.event_id
    ORDER BY e.created_at DESC NULLS LAST
    LIMIT 1
)
WHERE o.tenant_id IS NULL OR o.tenant_id = '';

-- SET NOT NULL is deliberately the migration's fail-closed guard: any legacy
-- row that cannot be mapped to an event aborts the migration instead of being
-- silently assigned to a default tenant.  Keep this SQL portable for H2 and
-- PostgreSQL; operators should reconcile the orphan rows before retrying.
ALTER TABLE t_ingestion_outbox ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_ingestion_outbox ALTER COLUMN tenant_id TYPE VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_tenant_due
    ON t_ingestion_outbox (tenant_id, status, next_attempt_at, created_at);
