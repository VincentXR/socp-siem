-- Older local databases may contain pre-tenancy playbooks. Adopt them into
-- the documented default tenant before making scheduler enumeration strict.
UPDATE t_playbook
SET tenant_id = 'default'
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';

ALTER TABLE t_playbook ALTER COLUMN tenant_id SET NOT NULL;

CREATE TABLE IF NOT EXISTS t_scheduled_playbook_run (
    id            VARCHAR(36) NOT NULL,
    tenant_id     VARCHAR(64) NOT NULL,
    playbook_id   VARCHAR(64) NOT NULL,
    scheduled_for TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    status        VARCHAR(16) NOT NULL,
    last_error    VARCHAR(1024),
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_scheduled_playbook_run PRIMARY KEY (id),
    CONSTRAINT uq_scheduled_playbook_fire UNIQUE (tenant_id, playbook_id, scheduled_for)
);

CREATE INDEX IF NOT EXISTS idx_scheduled_playbook_status_updated
    ON t_scheduled_playbook_run (status, updated_at);
