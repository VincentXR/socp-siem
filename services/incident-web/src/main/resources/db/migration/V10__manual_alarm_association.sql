-- A manual detach survives automatic delivery retries; explicit attach removes it.
CREATE TABLE t_incident_alarm_exclusion (
    tenant_id VARCHAR(64) NOT NULL,
    alarm_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, alarm_id)
);
