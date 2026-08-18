-- Source-event snapshots linked to an alert. The event_id allows future drill-down
-- into OpenSearch while the snapshot keeps the alert auditable after event rollover.
CREATE TABLE IF NOT EXISTS t_alarm_evidence (
    id              VARCHAR(255) NOT NULL,
    alarm_id        VARCHAR(255) NOT NULL,
    event_id        VARCHAR(255),
    event_timestamp TIMESTAMP(6) WITH TIME ZONE,
    source          VARCHAR(255),
    host            VARCHAR(255),
    severity        VARCHAR(32),
    raw             TEXT,
    fields_json     TEXT,
    evidence_order  INTEGER NOT NULL DEFAULT 0,
    tenant_id       VARCHAR(255),
    created_at      TIMESTAMP(6) WITH TIME ZONE,
    updated_at      TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT pk_t_alarm_evidence PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_alarm_evidence_alarm
    ON t_alarm_evidence (tenant_id, alarm_id, evidence_order);
CREATE INDEX IF NOT EXISTS idx_alarm_evidence_event
    ON t_alarm_evidence (tenant_id, event_id);
