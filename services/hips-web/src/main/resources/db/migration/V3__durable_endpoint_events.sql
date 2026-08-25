CREATE TABLE IF NOT EXISTS t_endpoint_event (
    event_id     VARCHAR(36) PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    hostname     VARCHAR(128),
    received_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    payload_json TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_endpoint_event_tenant_received
    ON t_endpoint_event (tenant_id, received_at DESC);
