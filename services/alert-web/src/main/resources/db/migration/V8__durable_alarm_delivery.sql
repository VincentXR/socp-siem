CREATE TABLE IF NOT EXISTS alarm_delivery (
    id              VARCHAR(36) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    alarm_id        VARCHAR(255) NOT NULL,
    destination     VARCHAR(32) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimed_at      TIMESTAMP(6) WITH TIME ZONE,
    delivered_at    TIMESTAMP(6) WITH TIME ZONE,
    last_error      VARCHAR(1024),
    trace_id        VARCHAR(64),
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_alarm_delivery PRIMARY KEY (id),
    CONSTRAINT uq_alarm_delivery_target UNIQUE (tenant_id, alarm_id, destination)
);

CREATE INDEX IF NOT EXISTS idx_alarm_delivery_due
    ON alarm_delivery (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_alarm_delivery_tenant_alarm
    ON alarm_delivery (tenant_id, alarm_id);
