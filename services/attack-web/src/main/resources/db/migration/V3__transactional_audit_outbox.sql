CREATE TABLE IF NOT EXISTS t_audit_outbox (
    event_id       VARCHAR(64) NOT NULL,
    tenant_id      VARCHAR(64) NOT NULL,
    action         VARCHAR(128) NOT NULL,
    operator_id    VARCHAR(255) NOT NULL,
    target_name    VARCHAR(255) NOT NULL,
    result_text    VARCHAR(1024) NOT NULL,
    payload        TEXT NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimed_at     TIMESTAMP(6) WITH TIME ZONE,
    claim_token    VARCHAR(64),
    published_at   TIMESTAMP(6) WITH TIME ZONE,
    last_error     VARCHAR(1024),
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_audit_outbox PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_audit_outbox_delivery
    ON t_audit_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_outbox_tenant_created
    ON t_audit_outbox (tenant_id, created_at);
