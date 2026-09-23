-- The row_version column already exists; upgraded signal claim/recovery queries
-- increment it so stale acknowledgements cannot overwrite a new attempt.
CREATE INDEX idx_soar_signal_stale_claim
    ON t_soar_signal_outbox (status, claimed_at, id);
