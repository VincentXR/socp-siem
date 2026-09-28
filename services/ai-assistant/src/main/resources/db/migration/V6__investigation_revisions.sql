ALTER TABLE t_ai_investigation ADD COLUMN IF NOT EXISTS revision INTEGER NOT NULL DEFAULT 1;

ALTER TABLE t_ai_investigation
    DROP CONSTRAINT IF EXISTS uq_ai_investigation_tenant_alert;

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_investigation_tenant_alert_revision
    ON t_ai_investigation (tenant_id, alert_id, revision);

CREATE INDEX IF NOT EXISTS idx_ai_investigation_tenant_alert_latest
    ON t_ai_investigation (tenant_id, alert_id, revision DESC);
