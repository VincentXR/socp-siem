ALTER TABLE t_analyzed ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'default';
UPDATE t_analyzed SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
ALTER TABLE t_analyzed ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_t_analyzed_tenant_ts ON t_analyzed (tenant_id, ts DESC);
