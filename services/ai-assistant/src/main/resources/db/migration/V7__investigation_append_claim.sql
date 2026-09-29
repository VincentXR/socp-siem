ALTER TABLE t_ai_investigation ADD COLUMN IF NOT EXISTS append_claim_token VARCHAR(36);
ALTER TABLE t_ai_investigation ADD COLUMN IF NOT EXISTS append_claim_until TIMESTAMP(6) WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_ai_investigation_append_claim
    ON t_ai_investigation (tenant_id, append_claim_until);
