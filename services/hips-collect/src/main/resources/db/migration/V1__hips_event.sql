CREATE TABLE IF NOT EXISTS t_hips_event (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_hips_event PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_hips_event_tenant_received
    ON t_hips_event (tenant_id, received_at);
