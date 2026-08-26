ALTER TABLE t_ai_investigation ADD COLUMN IF NOT EXISTS claim_owner VARCHAR(128);
ALTER TABLE t_ai_investigation ADD COLUMN IF NOT EXISTS claim_until TIMESTAMP(6) WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_ai_investigation_claim
    ON t_ai_investigation (status, claim_until);
