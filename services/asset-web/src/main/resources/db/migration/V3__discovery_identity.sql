-- Only collector discoveries are unique by tenant/IP. Manual NAT assets remain independent.
CREATE TABLE t_asset_discovery (
    tenant_id VARCHAR(64) NOT NULL,
    normalized_ip VARCHAR(64) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, normalized_ip),
    CONSTRAINT uq_asset_discovery_asset UNIQUE (asset_id),
    CONSTRAINT fk_asset_discovery_asset FOREIGN KEY (asset_id) REFERENCES t_asset(id) ON DELETE CASCADE
);
