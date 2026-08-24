CREATE TABLE IF NOT EXISTS t_asset_collection (
    id           VARCHAR(36) NOT NULL,
    tenant_id    VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    collected_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_asset_collection PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_asset_collection_tenant_collected
    ON t_asset_collection (tenant_id, collected_at);
