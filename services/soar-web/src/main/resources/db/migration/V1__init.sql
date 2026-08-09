-- 剧本表（H2/PG 兼容语法）
CREATE TABLE IF NOT EXISTS t_playbook (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    trigger_desc VARCHAR(256),
    actions VARCHAR(2048),
    enabled BOOLEAN NOT NULL,
    status VARCHAR(16),
    created_at TIMESTAMP NOT NULL,
    tenant_id VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_playbook_tenant ON t_playbook (tenant_id);
