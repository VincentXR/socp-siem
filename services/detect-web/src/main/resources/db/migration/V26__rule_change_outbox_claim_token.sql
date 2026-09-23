-- Each claim gets a fresh identity, including after an operator resets retries.
ALTER TABLE t_rule_change_outbox ADD COLUMN claim_token VARCHAR(36);
