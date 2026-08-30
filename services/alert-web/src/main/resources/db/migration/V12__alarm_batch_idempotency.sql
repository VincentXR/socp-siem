CREATE TABLE IF NOT EXISTS t_alarm_batch_idempotency (
    id              VARCHAR(36) NOT NULL,
    tenant_id       VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64) NOT NULL,
    response_json   TEXT NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE,
    updated_at      TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_alarm_batch_idempotency PRIMARY KEY (id),
    CONSTRAINT uq_alarm_batch_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_alarm_batch_idempotency_created
    ON t_alarm_batch_idempotency (tenant_id, created_at);
