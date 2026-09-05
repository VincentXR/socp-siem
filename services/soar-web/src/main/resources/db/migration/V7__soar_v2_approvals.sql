CREATE TABLE IF NOT EXISTS t_soar_approval (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    approver VARCHAR(128),
    reason VARCHAR(2048),
    decision_reason VARCHAR(2048),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT uq_soar_approval_run UNIQUE (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS idx_soar_approval_tenant_status
    ON t_soar_approval (tenant_id, status, created_at);
