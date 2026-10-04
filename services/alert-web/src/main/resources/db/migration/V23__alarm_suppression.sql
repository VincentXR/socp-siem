-- Analyst-confirmed suppression windows, keyed by detection scope so a confirmed
-- false positive silences future matches instead of dying in a process-local map.
CREATE TABLE IF NOT EXISTS t_alarm_suppression (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(255) NOT NULL,
    rule_id     VARCHAR(255) NOT NULL,
    entity_key  VARCHAR(255) NOT NULL,
    origin      VARCHAR(32) NOT NULL,
    reason      VARCHAR(4096) NOT NULL,
    alarm_id    VARCHAR(255),
    actor       VARCHAR(128),
    expires_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at  TIMESTAMP(6) WITH TIME ZONE,
    updated_at  TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_alarm_suppression PRIMARY KEY (id),
    CONSTRAINT uq_alarm_suppression_scope UNIQUE (tenant_id, rule_id, entity_key)
);

-- The alarm-creation path asks "is this scope currently suppressed" for every
-- incoming alarm, so the lookup must be an index range scan ending at now().
CREATE INDEX IF NOT EXISTS idx_alarm_suppression_active
    ON t_alarm_suppression (tenant_id, rule_id, entity_key, expires_at DESC);
