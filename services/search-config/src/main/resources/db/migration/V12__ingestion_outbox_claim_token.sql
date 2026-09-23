-- Stop old publishers before enabling token-fenced writers. Legacy PROCESSING
-- rows have no token and are recovered by the bounded stale-claim sweep.
ALTER TABLE t_ingestion_outbox ADD COLUMN claim_token VARCHAR(36);
