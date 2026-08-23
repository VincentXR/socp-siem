-- Make externally visible ids tenant-scoped without changing the existing
-- single-column JPA primary keys. The primary-key columns hold storage keys;
-- the original rule/event ids remain available in dedicated columns.
ALTER TABLE t_rule ADD COLUMN IF NOT EXISTS rule_id VARCHAR(64);
UPDATE t_rule SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE t_rule SET rule_id = id WHERE rule_id IS NULL;
ALTER TABLE t_rule ALTER COLUMN rule_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_rule_tenant_rule ON t_rule (tenant_id, rule_id);

ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS source_event_id VARCHAR(128);
UPDATE t_detection_event SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE t_detection_event SET source_event_id = event_id WHERE source_event_id IS NULL;
ALTER TABLE t_detection_event ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_detection_event ALTER COLUMN source_event_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_detection_event_tenant_source
    ON t_detection_event (tenant_id, source_event_id);
CREATE INDEX IF NOT EXISTS idx_detection_event_tenant_occurred
    ON t_detection_event (tenant_id, occurred_at);

ALTER TABLE t_entity_risk_profile ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE t_entity_risk_profile ADD COLUMN IF NOT EXISTS entity_key VARCHAR(512);
UPDATE t_entity_risk_profile SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE t_entity_risk_profile SET entity_key = entity_value WHERE entity_key IS NULL;
ALTER TABLE t_entity_risk_profile ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_entity_risk_profile ALTER COLUMN entity_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_entity_risk_tenant_entity
    ON t_entity_risk_profile (tenant_id, entity_key);
CREATE INDEX IF NOT EXISTS idx_entity_risk_tenant_score
    ON t_entity_risk_profile (tenant_id, score DESC);

ALTER TABLE t_entity_risk_alert ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
ALTER TABLE t_entity_risk_alert ADD COLUMN IF NOT EXISTS source_alert_id VARCHAR(128);
UPDATE t_entity_risk_alert SET tenant_id = 'default' WHERE tenant_id IS NULL OR tenant_id = '';
UPDATE t_entity_risk_alert SET source_alert_id = alert_id WHERE source_alert_id IS NULL;
ALTER TABLE t_entity_risk_alert ALTER COLUMN tenant_id SET NOT NULL;
ALTER TABLE t_entity_risk_alert ALTER COLUMN source_alert_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_entity_risk_alert_tenant_source
    ON t_entity_risk_alert (tenant_id, source_alert_id);
CREATE INDEX IF NOT EXISTS idx_entity_risk_alert_tenant_entity_created
    ON t_entity_risk_alert (tenant_id, entity_value, created_at);
