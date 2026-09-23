-- Upgrade all rule writers together. The row is both the tenant mutation lock
-- and the committed content-pack installation marker. Old writers ignore it.
CREATE TABLE t_rule_catalog (
    tenant_id        VARCHAR(64) PRIMARY KEY,
    pack_id          VARCHAR(128),
    pack_version     VARCHAR(64),
    pack_fingerprint VARCHAR(64),
    CONSTRAINT ck_rule_catalog_installation CHECK (
        (pack_id IS NULL AND pack_version IS NULL AND pack_fingerprint IS NULL)
        OR (pack_id IS NOT NULL AND pack_version IS NOT NULL AND pack_fingerprint IS NOT NULL)
    )
);
