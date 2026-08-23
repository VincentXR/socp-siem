CREATE TABLE IF NOT EXISTS t_alarm_case_link (
    id         VARCHAR(36) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL,
    alarm_id   VARCHAR(255) NOT NULL,
    case_id    VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_alarm_case_link PRIMARY KEY (id),
    CONSTRAINT uq_incident_alarm_link UNIQUE (tenant_id, alarm_id)
);

CREATE INDEX IF NOT EXISTS idx_alarm_case_link_case
    ON t_alarm_case_link (tenant_id, case_id);
