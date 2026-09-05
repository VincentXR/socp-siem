CREATE TABLE IF NOT EXISTS t_soar_automation_rule (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL,
    priority INTEGER NOT NULL,
    trigger_type VARCHAR(64) NOT NULL,
    condition_json TEXT NOT NULL,
    actions_json TEXT NOT NULL,
    suppression_json TEXT,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_soar_automation_rule_tenant_enabled
    ON t_soar_automation_rule (tenant_id, enabled, priority, updated_at);
