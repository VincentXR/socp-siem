-- New writes use normalized, independently indexed association rows. The
-- legacy JSON columns remain as a read fallback for pre-V7 rows and are
-- compacted to [] the next time a case is saved.
CREATE TABLE IF NOT EXISTS t_case_rule_link (
    id         VARCHAR(36) NOT NULL,
    tenant_id  VARCHAR(64) NOT NULL,
    case_id    VARCHAR(255) NOT NULL,
    rule_id    VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_case_rule_link PRIMARY KEY (id),
    CONSTRAINT uq_case_rule_link UNIQUE (tenant_id, case_id, rule_id)
);

CREATE INDEX IF NOT EXISTS idx_case_rule_link_case
    ON t_case_rule_link (tenant_id, case_id, rule_id);
