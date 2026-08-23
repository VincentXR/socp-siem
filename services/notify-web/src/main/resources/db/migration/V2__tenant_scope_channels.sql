ALTER TABLE t_channel ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
UPDATE t_channel SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
ALTER TABLE t_channel ALTER COLUMN tenant_id SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_channel_tenant ON t_channel (tenant_id);
