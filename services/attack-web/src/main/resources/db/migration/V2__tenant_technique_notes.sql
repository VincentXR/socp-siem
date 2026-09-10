-- Additive: keep the existing catalog intact. Rollback may leave this table in place.
CREATE TABLE IF NOT EXISTS t_technique_note (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    technique_id VARCHAR(16) NOT NULL,
    note VARCHAR(4000) NOT NULL DEFAULT '',
    version BIGINT,
    CONSTRAINT uq_technique_note_tenant UNIQUE (tenant_id, technique_id)
);
