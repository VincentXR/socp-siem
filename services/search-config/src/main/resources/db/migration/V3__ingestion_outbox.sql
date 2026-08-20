CREATE TABLE IF NOT EXISTS t_ingestion_outbox (
    id           VARCHAR(255) NOT NULL,
    event_id     VARCHAR(255) NOT NULL,
    routing_key  VARCHAR(512) NOT NULL,
    payload      TEXT NOT NULL,
    traceparent  VARCHAR(255),
    status       VARCHAR(32) NOT NULL,
    claimed_at   TIMESTAMP(6) WITH TIME ZONE,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    tenant_id    VARCHAR(255),
    created_at   TIMESTAMP(6) WITH TIME ZONE,
    updated_at   TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_t_ingestion_outbox PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_pending
    ON t_ingestion_outbox (status, created_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_claim
    ON t_ingestion_outbox (status, claimed_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_outbox_event
    ON t_ingestion_outbox (event_id);
