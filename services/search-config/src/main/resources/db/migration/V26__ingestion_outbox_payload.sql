-- New publication intents keep immutable evidence off the mutable lease row.
-- Existing payloads remain readable in place; no retained receipt is rewritten.
ALTER TABLE t_ingestion_outbox ADD CONSTRAINT uk_ingestion_outbox_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE t_ingestion_outbox_payload (
    id VARCHAR(255) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    stored_payload TEXT NOT NULL,
    CONSTRAINT pk_ingestion_outbox_payload PRIMARY KEY (id),
    CONSTRAINT fk_ingestion_outbox_payload FOREIGN KEY (id, tenant_id)
        REFERENCES t_ingestion_outbox(id, tenant_id) ON DELETE CASCADE ON UPDATE CASCADE
);

ALTER TABLE t_ingestion_outbox ALTER COLUMN payload SET DEFAULT '';
