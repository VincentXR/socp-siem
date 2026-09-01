CREATE INDEX IF NOT EXISTS idx_detection_alert_outbox_tenant_terminal
    ON t_detection_alert_outbox (tenant_id, status, updated_at);

CREATE INDEX IF NOT EXISTS idx_rule_change_outbox_tenant_terminal
    ON t_rule_change_outbox (tenant_id, status, updated_at);
