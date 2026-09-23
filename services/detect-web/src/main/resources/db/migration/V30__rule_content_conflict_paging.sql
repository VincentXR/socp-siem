CREATE INDEX idx_rule_conflict_tenant_pending_page
    ON t_rule_content_conflict (tenant_id, status, detected_at, id);
