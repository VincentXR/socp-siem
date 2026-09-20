-- Cross-dimension detection routing v2.
-- Source evidence identity, routed delivery identity and their transport positions
-- are persisted separately so fan-out copies cannot false-dedupe one another.

ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS delivery_id VARCHAR(128);
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS routing_version VARCHAR(64);
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS source_topic VARCHAR(255);
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS source_partition INTEGER;
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS source_offset BIGINT;
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS delivery_topic VARCHAR(255);
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS delivery_partition INTEGER;
ALTER TABLE t_detection_event ADD COLUMN IF NOT EXISTS delivery_offset BIGINT;

UPDATE t_detection_event
SET delivery_id = source_event_id
WHERE delivery_id IS NULL OR delivery_id = '';

UPDATE t_detection_event
SET routing_version = 'legacy-v1'
WHERE routing_version IS NULL OR routing_version = '';

UPDATE t_detection_event
SET source_topic = 'socp-events',
    source_partition = kafka_partition,
    source_offset = kafka_offset,
    delivery_topic = 'socp-events',
    delivery_partition = kafka_partition,
    delivery_offset = kafka_offset
WHERE kafka_partition IS NOT NULL;

ALTER TABLE t_detection_event ALTER COLUMN delivery_id SET NOT NULL;
ALTER TABLE t_detection_event ALTER COLUMN routing_version SET NOT NULL;

DROP INDEX IF EXISTS uk_detection_event_tenant_source;
CREATE UNIQUE INDEX IF NOT EXISTS uk_detection_event_tenant_delivery
    ON t_detection_event (tenant_id, delivery_id);
CREATE INDEX IF NOT EXISTS idx_detection_event_tenant_source
    ON t_detection_event (tenant_id, source_event_id);
CREATE INDEX IF NOT EXISTS idx_detection_event_delivery_position
    ON t_detection_event (delivery_topic, delivery_partition, delivery_offset);
CREATE INDEX IF NOT EXISTS idx_detection_event_source_position
    ON t_detection_event (source_topic, source_partition, source_offset);

CREATE TABLE IF NOT EXISTS t_detection_route_outbox (
    delivery_id        VARCHAR(64) PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    source_event_id    VARCHAR(128) NOT NULL,
    routing_version    VARCHAR(64) NOT NULL,
    plan_version       VARCHAR(64) NOT NULL,
    route_kind         VARCHAR(16) NOT NULL,
    route_dimension    VARCHAR(255) NOT NULL,
    route_value        VARCHAR(1024) NOT NULL,
    routing_key        VARCHAR(255) NOT NULL,
    source_topic       VARCHAR(255) NOT NULL,
    source_partition   INTEGER NOT NULL,
    source_offset      BIGINT NOT NULL,
    delivery_topic     VARCHAR(255) NOT NULL,
    delivery_partition INTEGER,
    delivery_offset    BIGINT,
    payload             TEXT NOT NULL,
    status              VARCHAR(16) NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at        TIMESTAMP(6) WITH TIME ZONE,
    last_error          VARCHAR(1024)
);

CREATE INDEX IF NOT EXISTS idx_detection_route_outbox_due
    ON t_detection_route_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_detection_route_outbox_source
    ON t_detection_route_outbox (source_topic, source_partition, source_offset);
CREATE INDEX IF NOT EXISTS idx_detection_route_outbox_delivery
    ON t_detection_route_outbox (delivery_topic, delivery_partition, delivery_offset);
CREATE INDEX IF NOT EXISTS idx_detection_route_outbox_tenant_source
    ON t_detection_route_outbox (tenant_id, source_event_id);

-- Every canonical Kafka record gets its own durable receipt. This remains
-- distinct from business-event delivery identity so duplicate source records
-- at different offsets can be acknowledged without emitting duplicate work.
CREATE TABLE IF NOT EXISTS t_detection_route_source (
    id                 VARCHAR(36) PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    source_topic       VARCHAR(255) NOT NULL,
    source_partition   INTEGER NOT NULL,
    source_offset      BIGINT NOT NULL,
    source_event_id    VARCHAR(128) NOT NULL,
    routing_version    VARCHAR(64) NOT NULL,
    plan_version       VARCHAR(64) NOT NULL,
    delivery_count     INTEGER NOT NULL,
    missing_dimensions VARCHAR(2048),
    status             VARCHAR(16) NOT NULL,
    status_reason      VARCHAR(1024),
    created_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_detection_route_source_position
        UNIQUE (source_topic, source_partition, source_offset)
);
CREATE INDEX IF NOT EXISTS idx_detection_route_source_event
    ON t_detection_route_source (tenant_id, source_event_id);

-- Routing topology is pinned durably per tenant and delivery routing version.
-- Normal rule tuning may keep the same topology fingerprint; changing grouping
-- dimensions or source coverage requires a new explicit routing-version cutover.
CREATE TABLE IF NOT EXISTS t_detection_route_topology (
    id              VARCHAR(36) PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    routing_version VARCHAR(64) NOT NULL,
    plan_version    VARCHAR(64) NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_detection_route_topology_tenant_version
        UNIQUE (tenant_id, routing_version)
);
CREATE INDEX IF NOT EXISTS idx_detection_route_topology_plan
    ON t_detection_route_topology (tenant_id, plan_version);

-- Snapshot generations are namespaced by input topic. Legacy and routed states
-- can coexist, which makes cutover rollback executable without state deletion.
UPDATE t_detection_state_snapshot
SET input_topic = 'socp-events'
WHERE input_topic IS NULL OR input_topic = '';

ALTER TABLE t_detection_state_snapshot ALTER COLUMN input_topic SET NOT NULL;
ALTER TABLE t_detection_state_snapshot
    DROP CONSTRAINT IF EXISTS uq_detection_state_snapshot_key;
DROP INDEX IF EXISTS uq_detection_state_snapshot_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_detection_state_snapshot_key
    ON t_detection_state_snapshot (tenant_id, rule_id, shard_id, input_topic);
