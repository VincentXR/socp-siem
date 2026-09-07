-- One run-wide execution counter shared by the top-level workflow, branches
-- and SUB_PLAYBOOK child workflows.  The Activity increments it while holding
-- the run row lock, so multiple SOAR instances cannot each spend a local
-- copy of maxNodeExecutions.
ALTER TABLE t_soar_run
    ADD COLUMN IF NOT EXISTS execution_node_count INTEGER NOT NULL DEFAULT 0;
