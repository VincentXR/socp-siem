-- Immutable approval vote/decision history.  The actor uniqueness constraint
-- makes repeated submissions idempotent and prevents one operator from
-- satisfying multiple required votes on the same gate.
CREATE TABLE IF NOT EXISTS t_soar_approval_decision (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    approval_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    reason VARCHAR(2048),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_soar_approval_decision_actor
        UNIQUE (tenant_id, approval_id, actor_id)
);
CREATE INDEX IF NOT EXISTS idx_soar_approval_decision_gate
    ON t_soar_approval_decision (tenant_id, approval_id, created_at);
