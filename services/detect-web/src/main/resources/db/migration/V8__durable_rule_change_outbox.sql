CREATE TABLE IF NOT EXISTS t_rule_change_outbox (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    rule_id         VARCHAR(128) NOT NULL,
    action          VARCHAR(32) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimed_at      TIMESTAMP(6) WITH TIME ZONE,
    published_at    TIMESTAMP(6) WITH TIME ZONE,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error      VARCHAR(1024)
);

CREATE INDEX IF NOT EXISTS idx_rule_change_outbox_due
    ON t_rule_change_outbox (status, next_attempt_at, created_at);
