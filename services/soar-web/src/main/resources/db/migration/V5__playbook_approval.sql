CREATE TABLE IF NOT EXISTS t_playbook_approval (
    approval_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    playbook_id VARCHAR(64) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    approved_by VARCHAR(128),
    reason VARCHAR(1024),
    scope_json TEXT,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    execution_id VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_playbook_approval_tenant_status
    ON t_playbook_approval (tenant_id, status, expires_at);
