-- Durable SOAR execution history. Action details remain JSON so new connector
-- result fields do not require a schema migration.
CREATE TABLE IF NOT EXISTS t_playbook_execution (
    execution_id VARCHAR(64) PRIMARY KEY,
    playbook_id  VARCHAR(64) NOT NULL,
    playbook     VARCHAR(128),
    status       VARCHAR(32) NOT NULL,
    trigger      VARCHAR(32),
    retry_count  INTEGER NOT NULL,
    error        VARCHAR(1024),
    results_json TEXT,
    ts           TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_playbook_execution_tenant_ts
    ON t_playbook_execution (tenant_id, ts);
