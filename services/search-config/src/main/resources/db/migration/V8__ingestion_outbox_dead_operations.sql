CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_tenant_terminal
    ON t_ingestion_outbox (tenant_id, status, updated_at);
