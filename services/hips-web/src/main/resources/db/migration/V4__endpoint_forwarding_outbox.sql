CREATE TABLE t_endpoint_forwarding (
    event_id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    claim_token VARCHAR(36),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    delivered_at TIMESTAMP(6) WITH TIME ZONE,
    last_error VARCHAR(256),
    CONSTRAINT ck_endpoint_forwarding_status CHECK (status IN ('PENDING','PROCESSING','DELIVERED','DEAD'))
);
CREATE INDEX idx_endpoint_forwarding_due ON t_endpoint_forwarding(status, next_attempt_at, event_id);
CREATE INDEX idx_endpoint_forwarding_tenant ON t_endpoint_forwarding(tenant_id, status, created_at, event_id);
CREATE INDEX idx_endpoint_forwarding_retention ON t_endpoint_forwarding(status, delivered_at, event_id);
CREATE TABLE t_endpoint_forwarding_admission (id INTEGER PRIMARY KEY);
INSERT INTO t_endpoint_forwarding_admission(id) VALUES (1);
-- Old history has no forwarding receipt: do not replay it implicitly.
