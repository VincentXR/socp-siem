CREATE TABLE IF NOT EXISTS t_soar_connector (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    connector_type VARCHAR(64) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    auth_secret_ref VARCHAR(255),
    allowed_hosts_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_soar_connector_name
    ON t_soar_connector (tenant_id, name);
CREATE INDEX IF NOT EXISTS idx_soar_connector_tenant_enabled
    ON t_soar_connector (tenant_id, enabled, connector_type);
