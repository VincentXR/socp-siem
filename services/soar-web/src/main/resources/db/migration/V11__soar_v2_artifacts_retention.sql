-- Large/non-inline action results are represented by an artifact reference.
CREATE TABLE IF NOT EXISTS t_soar_artifact (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    node_run_id VARCHAR(64),
    media_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(128) NOT NULL,
    storage_ref VARCHAR(2048) NOT NULL,
    classification VARCHAR(32) NOT NULL,
    inline_json TEXT,
    expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_soar_artifact_tenant_run
    ON t_soar_artifact (tenant_id, run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_soar_artifact_expiry
    ON t_soar_artifact (expires_at);
