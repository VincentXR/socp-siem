-- Durable execution controls: trigger receipts, attempts, human tasks and signals.
-- Additive migration; the V1-V9 schema is intentionally left untouched.

CREATE TABLE IF NOT EXISTS t_soar_trigger_receipt (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    automation_rule_id VARCHAR(64) NOT NULL,
    rule_revision INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    run_id VARCHAR(64),
    reason VARCHAR(2048),
    group_key VARCHAR(512),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_soar_trigger_receipt UNIQUE
        (tenant_id, event_id, automation_rule_id, rule_revision)
);
CREATE INDEX IF NOT EXISTS idx_soar_trigger_receipt_tenant_event
    ON t_soar_trigger_receipt (tenant_id, event_id, created_at);

CREATE TABLE IF NOT EXISTS t_soar_action_attempt (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    node_run_id VARCHAR(64) NOT NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_hash VARCHAR(128),
    remote_operation_id VARCHAR(255),
    receipt_json TEXT,
    error_code VARCHAR(128),
    error_message VARCHAR(2048),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_soar_action_attempt UNIQUE (tenant_id, node_run_id, attempt_no)
);
CREATE INDEX IF NOT EXISTS idx_soar_action_attempt_tenant_node
    ON t_soar_action_attempt (tenant_id, node_run_id, attempt_no);

CREATE TABLE IF NOT EXISTS t_soar_manual_task (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    form_schema_json TEXT NOT NULL,
    input_json TEXT,
    assignee VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    due_at TIMESTAMP(6) WITH TIME ZONE,
    completed_by VARCHAR(128),
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_soar_manual_task_node UNIQUE (tenant_id, run_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_soar_manual_task_tenant_status
    ON t_soar_manual_task (tenant_id, status, due_at, created_at);

CREATE TABLE IF NOT EXISTS t_soar_signal_outbox (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    signal_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimed_by VARCHAR(128),
    claimed_at TIMESTAMP(6) WITH TIME ZONE,
    last_error VARCHAR(2048),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_soar_signal_outbox_business UNIQUE (tenant_id, run_id, signal_type)
);
CREATE INDEX IF NOT EXISTS idx_soar_signal_outbox_pending
    ON t_soar_signal_outbox (status, next_attempt_at, created_at);

ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS config_json TEXT;
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS secret_refs_json TEXT;
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS scope_json TEXT;
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS status VARCHAR(24);
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS revision INTEGER DEFAULT 1;
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS last_test_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS last_test_status VARCHAR(24);
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS last_test_error VARCHAR(2048);
ALTER TABLE t_soar_connector ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_soar_dispatch_outbox ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE t_soar_signal_outbox ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS revision INTEGER DEFAULT 1;
ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS dedup_window_seconds BIGINT;
ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS cooldown_seconds BIGINT;
ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS group_by VARCHAR(512);
ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS max_concurrent_runs INTEGER;
ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS conflict_strategy VARCHAR(24);
ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS valid_from TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE t_soar_automation_rule ADD COLUMN IF NOT EXISTS valid_until TIMESTAMP(6) WITH TIME ZONE;
