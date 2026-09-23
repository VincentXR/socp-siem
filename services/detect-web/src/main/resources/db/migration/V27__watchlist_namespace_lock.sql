-- Mutating writers must be upgraded together: namespace locks serialize
-- append/replace/delete and enforce quotas across replicas.
CREATE TABLE t_watchlist_namespace (
    tenant_id VARCHAR(64) PRIMARY KEY
);
