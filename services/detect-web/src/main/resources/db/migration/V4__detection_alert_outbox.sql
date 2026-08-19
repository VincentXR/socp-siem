-- Durable Detection -> Alert Web hand-off. The deterministic alert id is the
-- primary key so replay and duplicate delivery remain idempotent.
CREATE TABLE IF NOT EXISTS t_detection_alert_outbox (
    alert_id          VARCHAR(255) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    payload           TEXT NOT NULL,
    status            VARCHAR(16) NOT NULL,
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    delivered_at      TIMESTAMP(6) WITH TIME ZONE,
    published_at      TIMESTAMP(6) WITH TIME ZONE,
    last_error        VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_detection_alert_outbox_due
    ON t_detection_alert_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_detection_alert_outbox_updated
    ON t_detection_alert_outbox (updated_at);
