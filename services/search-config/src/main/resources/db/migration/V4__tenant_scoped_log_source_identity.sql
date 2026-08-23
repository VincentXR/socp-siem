ALTER TABLE t_log_source ADD COLUMN IF NOT EXISTS source_id VARCHAR(255);
UPDATE t_log_source SET source_id = id WHERE source_id IS NULL;
UPDATE t_log_source SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
ALTER TABLE t_log_source ALTER COLUMN source_id SET NOT NULL;
ALTER TABLE t_log_source ALTER COLUMN tenant_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_log_source_tenant_id
    ON t_log_source (tenant_id, source_id);
