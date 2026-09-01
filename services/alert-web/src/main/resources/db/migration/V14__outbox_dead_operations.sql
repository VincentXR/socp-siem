CREATE INDEX IF NOT EXISTS idx_outbox_tenant_terminal
    ON outbox_event (tenant_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_alarm_delivery_tenant_terminal
    ON alarm_delivery (tenant_id, status, updated_at);
