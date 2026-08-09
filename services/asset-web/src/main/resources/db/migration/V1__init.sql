-- 资产表（H2/PG 兼容语法）
CREATE TABLE IF NOT EXISTS t_asset (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    ip VARCHAR(64),
    os VARCHAR(64),
    owner VARCHAR(64),
    criticality VARCHAR(16),
    created_at TIMESTAMP NOT NULL,
    tenant_id VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_asset_tenant ON t_asset (tenant_id);
CREATE INDEX IF NOT EXISTS idx_asset_ip ON t_asset (ip);
