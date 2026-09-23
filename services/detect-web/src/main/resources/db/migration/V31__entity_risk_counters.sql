ALTER TABLE t_entity_risk_profile ADD COLUMN counters_migrated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE t_entity_risk_profile ADD CONSTRAINT ck_entity_risk_counter_format
    CHECK (NOT counters_migrated OR (mitre_json='{}' AND rules_json='{}'));
ALTER TABLE t_entity_risk_profile ADD CONSTRAINT uk_entity_risk_profile_tenant_storage UNIQUE (tenant_id, entity_value);

CREATE TABLE t_entity_risk_counter (
    counter_id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    profile_id VARCHAR(512) NOT NULL,
    dimension VARCHAR(8) NOT NULL CHECK (dimension IN ('MITRE', 'RULE')),
    member_hash VARCHAR(64) NOT NULL,
    member_key_json TEXT NOT NULL,
    hit_count BIGINT NOT NULL CHECK (hit_count >= 0),
    CONSTRAINT uk_entity_risk_counter_member UNIQUE (tenant_id, profile_id, dimension, member_hash),
    CONSTRAINT fk_entity_risk_counter_profile FOREIGN KEY (tenant_id, profile_id)
        REFERENCES t_entity_risk_profile(tenant_id, entity_value) ON DELETE CASCADE
);
CREATE INDEX idx_entity_risk_counter_rank ON t_entity_risk_counter(tenant_id, profile_id, dimension, hit_count DESC);
