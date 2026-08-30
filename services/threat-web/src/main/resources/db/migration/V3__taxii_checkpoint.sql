CREATE TABLE IF NOT EXISTS t_taxii_checkpoint (
    checkpoint_id VARCHAR(256) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    feed VARCHAR(128) NOT NULL,
    collection_url VARCHAR(1024) NOT NULL,
    last_synced_at TIMESTAMP(6) WITH TIME ZONE,
    last_page INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1024),
    CONSTRAINT uk_taxii_checkpoint_tenant_feed UNIQUE (tenant_id, feed)
);
