CREATE TABLE IF NOT EXISTS t_alarm_feedback (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(255) NOT NULL,
    alarm_id    VARCHAR(255) NOT NULL,
    kind        VARCHAR(32) NOT NULL,
    reason      VARCHAR(4096) NOT NULL,
    expires_at  TIMESTAMP(6) WITH TIME ZONE,
    actor       VARCHAR(128),
    created_at  TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_alarm_feedback PRIMARY KEY (id),
    CONSTRAINT uq_alarm_feedback_tenant_alarm_kind UNIQUE (tenant_id, alarm_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_alarm_feedback_alarm
    ON t_alarm_feedback (tenant_id, alarm_id, created_at DESC);
