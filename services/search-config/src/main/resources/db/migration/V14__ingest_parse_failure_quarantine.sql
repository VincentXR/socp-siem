CREATE TABLE IF NOT EXISTS t_ingest_parse_failure (
    id                VARCHAR(36) NOT NULL,
    tenant_id         VARCHAR(255) NOT NULL,
    failure_key       VARCHAR(255) NOT NULL,
    collector_id      VARCHAR(255) NOT NULL,
    raw_payload       TEXT NOT NULL,
    received_at       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    parser_version    VARCHAR(255) NOT NULL,
    failure_reason    VARCHAR(1024) NOT NULL,
    replay_status     VARCHAR(16) NOT NULL,
    replay_attempts   INTEGER NOT NULL DEFAULT 0,
    replayed_event_id VARCHAR(255),
    replayed_at       TIMESTAMP(6) WITH TIME ZONE,
    last_error        VARCHAR(1024),
    created_at        TIMESTAMP(6) WITH TIME ZONE,
    updated_at        TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_ingest_parse_failure PRIMARY KEY (id),
    CONSTRAINT uq_ingest_parse_failure_key UNIQUE (tenant_id, failure_key)
);

CREATE INDEX IF NOT EXISTS idx_ingest_parse_failure_pending
    ON t_ingest_parse_failure (tenant_id, replay_status, received_at);
