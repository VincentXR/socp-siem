ALTER TABLE t_case ALTER COLUMN rule_ids TYPE TEXT;
ALTER TABLE t_case ALTER COLUMN alarm_ids TYPE TEXT;
ALTER TABLE t_case ALTER COLUMN timeline TYPE TEXT;
ALTER TABLE t_case ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS t_case_timeline (
    id         VARCHAR(36) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL,
    case_id    VARCHAR(255) NOT NULL,
    event_key  VARCHAR(255) NOT NULL,
    ts         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    type       VARCHAR(32) NOT NULL,
    message    TEXT,
    source     VARCHAR(64),
    alarm_id   VARCHAR(255),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_case_timeline PRIMARY KEY (id),
    CONSTRAINT uq_case_timeline_event UNIQUE (tenant_id, case_id, event_key)
);

CREATE INDEX IF NOT EXISTS idx_case_timeline_case_ts
    ON t_case_timeline (tenant_id, case_id, ts);
