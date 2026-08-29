-- Make the alert publication intent tenant-addressable.  Replay and
-- maintenance code must not infer a tenant by first loading the aggregate.
ALTER TABLE outbox_event ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);

UPDATE outbox_event e
SET tenant_id = (SELECT a.tenant_id FROM t_alarm a WHERE a.id = e.aggregate_id);

-- SET NOT NULL is deliberately the migration's fail-closed guard: any legacy
-- row that cannot be mapped to an alarm aborts the migration instead of being
-- silently assigned to a default tenant.  Keep this SQL portable for the H2
-- migration test as well as PostgreSQL production.
ALTER TABLE outbox_event ALTER COLUMN tenant_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_tenant_due
    ON outbox_event (tenant_id, status, next_attempt_at, created_at);
