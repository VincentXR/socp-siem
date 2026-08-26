CREATE TABLE IF NOT EXISTS t_ai_investigation (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    alert_id    VARCHAR(128) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    result_json TEXT NOT NULL,
    incident_id VARCHAR(255),
    appended_at TIMESTAMP(6) WITH TIME ZONE,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_ai_investigation PRIMARY KEY (id),
    CONSTRAINT uq_ai_investigation_tenant_alert UNIQUE (tenant_id, alert_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_investigation_tenant_updated
    ON t_ai_investigation (tenant_id, updated_at);
