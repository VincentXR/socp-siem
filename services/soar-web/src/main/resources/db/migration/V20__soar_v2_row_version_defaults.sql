-- New V2 entities are inserted through Spring Data save() with an assigned
-- UUID and no optimistic-lock version yet (Persistable.isNew is version-based).
-- A NOT NULL row_version column therefore needs a DEFAULT so the insert that
-- Hibernate performs for a brand-new versioned row never fails on H2/PostgreSQL.
ALTER TABLE t_soar_playbook ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_playbook_version ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_automation_rule ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_connector ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_run ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_dispatch_outbox ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_node_run ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_manual_task ALTER COLUMN row_version SET DEFAULT 0;
ALTER TABLE t_soar_signal_outbox ALTER COLUMN row_version SET DEFAULT 0;
