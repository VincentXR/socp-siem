-- Durable event-id claims and recent canonical events used to rebuild
-- threshold/correlation/baseline/rare state after detect-web restarts.
CREATE TABLE IF NOT EXISTS t_detection_event (
    event_id     VARCHAR(128) PRIMARY KEY,
    source       VARCHAR(64) NOT NULL,
    host         VARCHAR(255) NOT NULL,
    raw_event    VARCHAR(8192),
    fields_json  TEXT NOT NULL,
    severity     VARCHAR(16) NOT NULL,
    occurred_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_detection_event_occurred ON t_detection_event (occurred_at);
