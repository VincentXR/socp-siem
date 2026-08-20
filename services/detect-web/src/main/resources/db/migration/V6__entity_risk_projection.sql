-- Shared, restart-safe UEBA/entity-risk projection.  Alert ids are the
-- idempotency boundary; every Detection instance reads the same profiles.
CREATE TABLE IF NOT EXISTS t_entity_risk_profile (
    entity_value VARCHAR(512) PRIMARY KEY,
    score DOUBLE PRECISION NOT NULL,
    score_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    alert_count BIGINT NOT NULL,
    first_seen TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_seen TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    max_severity VARCHAR(16) NOT NULL,
    mitre_json VARCHAR(8192) NOT NULL,
    rules_json VARCHAR(8192) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_entity_risk_score ON t_entity_risk_profile (score DESC);

CREATE TABLE IF NOT EXISTS t_entity_risk_alert (
    alert_id VARCHAR(128) PRIMARY KEY,
    entity_value VARCHAR(512) NOT NULL,
    score INTEGER NOT NULL,
    risk_level VARCHAR(16) NOT NULL,
    breakdown_json VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_entity_risk_alert_entity_created
    ON t_entity_risk_alert (entity_value, created_at);
