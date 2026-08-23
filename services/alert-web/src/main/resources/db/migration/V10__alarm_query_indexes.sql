-- List and dashboard reads are always tenant-scoped. These composite indexes keep
-- database pagination and aggregation scans bounded without relying on JVM sorting.
CREATE INDEX IF NOT EXISTS idx_t_alarm_tenant_status_occurred
    ON t_alarm (tenant_id, status, occurred_at);

CREATE INDEX IF NOT EXISTS idx_t_alarm_tenant_rule_occurred
    ON t_alarm (tenant_id, rule_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_t_alarm_tenant_severity_occurred
    ON t_alarm (tenant_id, severity, occurred_at);

CREATE INDEX IF NOT EXISTS idx_t_alarm_tenant_risk_score
    ON t_alarm (tenant_id, risk_score);
