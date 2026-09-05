-- SOAR 2.0 control plane and durable execution projection.
-- This migration is additive; V1-V5 legacy tables remain untouched during migration.

CREATE TABLE IF NOT EXISTS t_soar_playbook (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(2048),
    owner VARCHAR(128),
    tags_json TEXT,
    status VARCHAR(24) NOT NULL,
    latest_published_version INTEGER,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_soar_playbook_tenant_status
    ON t_soar_playbook (tenant_id, status, updated_at);

CREATE TABLE IF NOT EXISTS t_soar_playbook_version (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    playbook_id VARCHAR(64) NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    definition_json TEXT NOT NULL,
    layout_json TEXT,
    definition_hash VARCHAR(128) NOT NULL,
    risk_summary_json TEXT,
    created_by VARCHAR(128) NOT NULL,
    published_by VARCHAR(128),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_soar_playbook_version UNIQUE (tenant_id, playbook_id, version_no)
);
CREATE INDEX IF NOT EXISTS idx_soar_playbook_version_tenant_status
    ON t_soar_playbook_version (tenant_id, status, updated_at);

CREATE TABLE IF NOT EXISTS t_soar_run (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    execution_series_id VARCHAR(64) NOT NULL,
    playbook_id VARCHAR(64) NOT NULL,
    playbook_version_id VARCHAR(64) NOT NULL,
    playbook_version_no INTEGER NOT NULL,
    definition_hash VARCHAR(128) NOT NULL,
    trigger_type VARCHAR(64) NOT NULL,
    subject_type VARCHAR(64),
    subject_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    temporal_workflow_id VARCHAR(128),
    temporal_run_id VARCHAR(128),
    input_json TEXT,
    output_json TEXT,
    error_code VARCHAR(128),
    error_message VARCHAR(2048),
    requested_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_soar_run_request UNIQUE (tenant_id, request_id)
);
CREATE INDEX IF NOT EXISTS idx_soar_run_tenant_status_created
    ON t_soar_run (tenant_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_soar_run_tenant_subject
    ON t_soar_run (tenant_id, subject_type, subject_id, created_at);

CREATE TABLE IF NOT EXISTS t_soar_dispatch_outbox (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimed_by VARCHAR(128),
    claimed_at TIMESTAMP(6) WITH TIME ZONE,
    last_error VARCHAR(2048),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_soar_dispatch_run UNIQUE (tenant_id, run_id)
);
CREATE INDEX IF NOT EXISTS idx_soar_dispatch_pending
    ON t_soar_dispatch_outbox (status, next_attempt_at, created_at);

CREATE TABLE IF NOT EXISTS t_soar_node_run (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    iteration_path VARCHAR(512) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json TEXT,
    output_json TEXT,
    idempotency_key VARCHAR(255),
    error_code VARCHAR(128),
    error_message VARCHAR(2048),
    started_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_soar_node_run UNIQUE (tenant_id, run_id, node_id, iteration_path)
);
CREATE INDEX IF NOT EXISTS idx_soar_node_run_tenant_run
    ON t_soar_node_run (tenant_id, run_id, updated_at);

CREATE TABLE IF NOT EXISTS t_soar_run_event (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    node_run_id VARCHAR(64),
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor VARCHAR(128),
    summary VARCHAR(1024) NOT NULL,
    detail_json TEXT,
    trace_id VARCHAR(128),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_soar_run_event_sequence UNIQUE (tenant_id, run_id, sequence_no)
);
CREATE INDEX IF NOT EXISTS idx_soar_run_event_tenant_run
    ON t_soar_run_event (tenant_id, run_id, sequence_no);
