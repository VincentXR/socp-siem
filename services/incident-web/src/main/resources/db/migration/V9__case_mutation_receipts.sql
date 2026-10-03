CREATE TABLE t_case_mutation (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(255) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_case_mutation_request UNIQUE (tenant_id, case_id, request_key)
);
CREATE INDEX idx_case_owner_queue ON t_case (tenant_id, assignee, updated_at, id);
