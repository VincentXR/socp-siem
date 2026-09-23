CREATE INDEX idx_soar_dispatch_claim_recovery ON t_soar_dispatch_outbox(status, claimed_at, id);
