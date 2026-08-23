CREATE TABLE IF NOT EXISTS t_alarm_evaluation (
    id          VARCHAR(36) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    alarm_id    VARCHAR(255) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    result_json TEXT,
    last_error  VARCHAR(1024),
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_alarm_evaluation PRIMARY KEY (id),
    CONSTRAINT uq_soar_alarm_evaluation UNIQUE (tenant_id, alarm_id)
);

CREATE INDEX IF NOT EXISTS idx_alarm_evaluation_status_updated
    ON t_alarm_evaluation (status, updated_at);
