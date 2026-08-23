ALTER TABLE t_endpoint ADD COLUMN IF NOT EXISTS endpoint_id VARCHAR(64);
ALTER TABLE t_endpoint ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) DEFAULT 'default';

UPDATE t_endpoint SET endpoint_id = id WHERE endpoint_id IS NULL;
UPDATE t_endpoint SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';

ALTER TABLE t_endpoint ALTER COLUMN endpoint_id SET NOT NULL;
ALTER TABLE t_endpoint ALTER COLUMN tenant_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_endpoint_tenant_id
    ON t_endpoint (tenant_id, endpoint_id);
CREATE INDEX IF NOT EXISTS idx_endpoint_tenant
    ON t_endpoint (tenant_id);
