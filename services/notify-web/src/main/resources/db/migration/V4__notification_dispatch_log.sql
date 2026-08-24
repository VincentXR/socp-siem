CREATE TABLE IF NOT EXISTS t_notification_dispatch_log (
    id           VARCHAR(36) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    alarm_id     VARCHAR(255) NOT NULL,
    channel_name VARCHAR(128) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    status       VARCHAR(32) NOT NULL,
    result_json  TEXT NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_dispatch_log PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_notification_dispatch_log_tenant_created
    ON t_notification_dispatch_log (tenant_id, created_at);
