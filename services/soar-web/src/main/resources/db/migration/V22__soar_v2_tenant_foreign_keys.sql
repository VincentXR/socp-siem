-- Enforce the ownership graph for SOAR projections at the database edge.
-- Every reference includes tenant_id.  The application still performs the
-- explicit tenant checks (and PostgreSQL RLS remains the outer boundary), but
-- these constraints prevent partial transactions or manual SQL from leaving
-- orphaned execution evidence.  Deletes do not cascade: retention is explicit
-- and audited.

ALTER TABLE t_soar_playbook
    ADD CONSTRAINT uq_soar_playbook_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_soar_playbook_version
    ADD CONSTRAINT uq_soar_playbook_version_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_soar_run
    ADD CONSTRAINT uq_soar_run_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_soar_node_run
    ADD CONSTRAINT uq_soar_node_run_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_soar_approval
    ADD CONSTRAINT uq_soar_approval_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_soar_automation_rule
    ADD CONSTRAINT uq_soar_automation_rule_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE t_soar_connector
    ADD CONSTRAINT uq_soar_connector_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE t_soar_playbook_version
    ADD CONSTRAINT fk_soar_version_playbook
    FOREIGN KEY (tenant_id, playbook_id)
    REFERENCES t_soar_playbook (tenant_id, id);

ALTER TABLE t_soar_run
    ADD CONSTRAINT fk_soar_run_playbook
    FOREIGN KEY (tenant_id, playbook_id)
    REFERENCES t_soar_playbook (tenant_id, id);
ALTER TABLE t_soar_run
    ADD CONSTRAINT fk_soar_run_version
    FOREIGN KEY (tenant_id, playbook_version_id)
    REFERENCES t_soar_playbook_version (tenant_id, id);

ALTER TABLE t_soar_dispatch_outbox
    ADD CONSTRAINT fk_soar_dispatch_run
    FOREIGN KEY (tenant_id, run_id)
    REFERENCES t_soar_run (tenant_id, id);
ALTER TABLE t_soar_node_run
    ADD CONSTRAINT fk_soar_node_run_run
    FOREIGN KEY (tenant_id, run_id)
    REFERENCES t_soar_run (tenant_id, id);
ALTER TABLE t_soar_node_run
    ADD CONSTRAINT fk_soar_node_run_connection
    FOREIGN KEY (tenant_id, connection_id)
    REFERENCES t_soar_connector (tenant_id, id);

ALTER TABLE t_soar_run_event
    ADD CONSTRAINT fk_soar_run_event_run
    FOREIGN KEY (tenant_id, run_id)
    REFERENCES t_soar_run (tenant_id, id);
ALTER TABLE t_soar_run_event
    ADD CONSTRAINT fk_soar_run_event_node
    FOREIGN KEY (tenant_id, node_run_id)
    REFERENCES t_soar_node_run (tenant_id, id);

ALTER TABLE t_soar_approval
    ADD CONSTRAINT fk_soar_approval_run
    FOREIGN KEY (tenant_id, run_id)
    REFERENCES t_soar_run (tenant_id, id);
ALTER TABLE t_soar_approval
    ADD CONSTRAINT fk_soar_approval_node
    FOREIGN KEY (tenant_id, node_run_id)
    REFERENCES t_soar_node_run (tenant_id, id);
ALTER TABLE t_soar_approval_decision
    ADD CONSTRAINT fk_soar_approval_decision_approval
    FOREIGN KEY (tenant_id, approval_id)
    REFERENCES t_soar_approval (tenant_id, id);

ALTER TABLE t_soar_trigger_receipt
    ADD CONSTRAINT fk_soar_receipt_rule
    FOREIGN KEY (tenant_id, automation_rule_id)
    REFERENCES t_soar_automation_rule (tenant_id, id);
ALTER TABLE t_soar_action_attempt
    ADD CONSTRAINT fk_soar_attempt_node
    FOREIGN KEY (tenant_id, node_run_id)
    REFERENCES t_soar_node_run (tenant_id, id);
ALTER TABLE t_soar_action_attempt
    ADD CONSTRAINT fk_soar_attempt_connection
    FOREIGN KEY (tenant_id, connection_id)
    REFERENCES t_soar_connector (tenant_id, id);

ALTER TABLE t_soar_manual_task
    ADD CONSTRAINT fk_soar_manual_task_run
    FOREIGN KEY (tenant_id, run_id)
    REFERENCES t_soar_run (tenant_id, id);
ALTER TABLE t_soar_signal_outbox
    ADD CONSTRAINT fk_soar_signal_run
    FOREIGN KEY (tenant_id, run_id)
    REFERENCES t_soar_run (tenant_id, id);

ALTER TABLE t_soar_artifact
    ADD CONSTRAINT fk_soar_artifact_run
    FOREIGN KEY (tenant_id, run_id)
    REFERENCES t_soar_run (tenant_id, id);
ALTER TABLE t_soar_artifact
    ADD CONSTRAINT fk_soar_artifact_node
    FOREIGN KEY (tenant_id, node_run_id)
    REFERENCES t_soar_node_run (tenant_id, id);
