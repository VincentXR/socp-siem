-- Support tenant-scoped severity aggregation without scanning other tenants.
CREATE INDEX IF NOT EXISTS idx_t_analyzed_tenant_severity
    ON t_analyzed (tenant_id, severity);
