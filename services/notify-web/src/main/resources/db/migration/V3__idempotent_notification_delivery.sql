CREATE TABLE IF NOT EXISTS t_notification_delivery (
    id           VARCHAR(36) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    alarm_id     VARCHAR(255) NOT NULL,
    channel_id   VARCHAR(64) NOT NULL,
    result_json  TEXT NOT NULL,
    delivered_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_delivery PRIMARY KEY (id),
    CONSTRAINT uq_notification_alarm_channel UNIQUE (tenant_id, alarm_id, channel_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_delivery_tenant_alarm
    ON t_notification_delivery (tenant_id, alarm_id);
