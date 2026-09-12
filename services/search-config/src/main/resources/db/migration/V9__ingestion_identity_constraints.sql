-- Make the producer event identity a tenant-local idempotency boundary.
-- Existing duplicate identities must be reconciled before this migration is
-- applied; failing closed is safer than silently deleting searchable events.
ALTER TABLE t_search_event ADD COLUMN IF NOT EXISTS payload_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_search_event_tenant_event
    ON t_search_event (tenant_id, event_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ingestion_outbox_tenant_event
    ON t_ingestion_outbox (tenant_id, event_id);
