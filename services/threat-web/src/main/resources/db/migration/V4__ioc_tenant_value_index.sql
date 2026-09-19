-- idx_t_ioc_tenant_value backs the IocStore (tenant_id, ioc_value) enrichment
-- lookup on cache miss. It was previously appended to the already-published
-- V1, which broke Flyway's checksum for existing databases and meant V1 never
-- re-ran to create the index there. It is restored to V1's original content and
-- re-delivered here so every database reaches the same schema. Mirrors the
-- alert-web V1/V4 pattern.
CREATE INDEX IF NOT EXISTS idx_t_ioc_tenant_value ON t_ioc (tenant_id, ioc_value);
