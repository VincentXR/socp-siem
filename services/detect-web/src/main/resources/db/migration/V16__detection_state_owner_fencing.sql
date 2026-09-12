-- Durable lease/fencing token for one input topic, Kafka partition and state shard.
-- A new owner may take the row only after the previous lease expires; every
-- takeover increments fencing_epoch so stale workers fail compare-and-set checks.
CREATE TABLE IF NOT EXISTS t_detection_state_owner (
    owner_key       VARCHAR(512) PRIMARY KEY,
    input_topic     VARCHAR(255) NOT NULL,
    input_partition INTEGER NOT NULL,
    state_shard     INTEGER NOT NULL,
    owner_id        VARCHAR(128) NOT NULL,
    fencing_epoch   BIGINT NOT NULL,
    lease_until     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_detection_state_owner_unit
        UNIQUE (input_topic, input_partition, state_shard)
);

CREATE INDEX IF NOT EXISTS idx_detection_state_owner_lease
    ON t_detection_state_owner (lease_until);
