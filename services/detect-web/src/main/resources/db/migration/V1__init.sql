-- 检测规则表（H2/PG 兼容语法）
CREATE TABLE IF NOT EXISTS t_rule (
    id VARCHAR(64) PRIMARY KEY,
    spec VARCHAR(4096) NOT NULL,
    tenant_id VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_rule_tenant ON t_rule (tenant_id);
