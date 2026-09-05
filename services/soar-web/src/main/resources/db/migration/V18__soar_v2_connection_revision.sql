-- Record which connection (and which revision of it) produced a side effect.
-- Audit/run evidence can then show exactly which connection configuration was
-- in effect for an attempt without needing to replay history, and a secret
-- rotation that bumps the connection revision no longer requires re-running
-- the whole playbook to explain an old outcome.
ALTER TABLE t_soar_node_run ADD COLUMN IF NOT EXISTS connection_id VARCHAR(64);
ALTER TABLE t_soar_node_run ADD COLUMN IF NOT EXISTS connection_revision INT;
ALTER TABLE t_soar_action_attempt ADD COLUMN IF NOT EXISTS connection_id VARCHAR(64);
ALTER TABLE t_soar_action_attempt ADD COLUMN IF NOT EXISTS connection_revision INT;
