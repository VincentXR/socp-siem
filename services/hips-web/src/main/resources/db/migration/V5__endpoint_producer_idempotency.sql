ALTER TABLE t_endpoint_forwarding ADD COLUMN producer_hash VARCHAR(64);
ALTER TABLE t_endpoint_forwarding ADD COLUMN request_key_hash VARCHAR(64);
ALTER TABLE t_endpoint_forwarding ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE t_endpoint_forwarding ADD CONSTRAINT ck_endpoint_forwarding_request_identity CHECK (
    (producer_hash IS NULL AND request_key_hash IS NULL AND request_fingerprint IS NULL)
    OR (producer_hash IS NOT NULL AND request_key_hash IS NOT NULL AND request_fingerprint IS NOT NULL)
);
CREATE UNIQUE INDEX uq_endpoint_forwarding_request
    ON t_endpoint_forwarding(tenant_id, producer_hash, request_key_hash);
-- Legacy receipts have no trustworthy producer/request key; never invent one.
