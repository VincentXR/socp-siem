-- Cross-dimension detection routing v2.
--
-- Existing journal rows remain valid legacy deliveries. New routed deliveries
-- separate source evidence identity from delivery identity and carry source and
-- delivery transport positions independently.
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

-- Before v2 the single Kafka position was both evidence transport and state
-- ownership transport. Preserve it in both namespaces for rollback/replay.
UPDATE t_detection_event
SET source_partition = kafka_partition
WHERE source_partition IS NULL AND kafka_partition IS NOT NULL;
UPDATE t_detection_event
SET source_offset = kafka_offset
WHERE source_offset IS NULL AND kafka_offset IS NOT NULL;
UPDATE t_detection_event
SET delivery_partition = kafka_partition
WHERE delivery_partition IS NULL AND kafka_partition IS NOT NULL;
UPDATE t_detection_event
SET delivery_offset = kafka_offset
WHERE delivery_offset IS NULL AND kafka_offset IS NOT NULL;
UPDATE t_detection_event
SET source_topic = 'socp-events'
WHERE source_topic IS NULL AND kafka_partition IS NOT NULL;
UPDATE t_detection_event
SET delivery_topic = 'socp-events'
WHERE delivery_topic IS NULL AND kafka_partition IS NOT NULL;

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

-- One durable receipt per immutable canonical Kafka record. The receipt makes
-- a source offset replay-safe even when no required routing dimension exists.
CREATE TABLE IF NOT EXISTS t_detection_route_source (
    source_key          VARCHAR(512) PRIMARY KEY,
    source_topic        VARCHAR(255) NOT NULL,
    source_partition    INTEGER NOT NULL,
    source_offset       BIGINT NOT NULL,
    tenant_id           VARCHAR(64) NOT NULL,
    source_event_id     VARCHAR(128) NOT NULL,
    routing_version     VARCHAR(64) NOT NULL,
    plan_version        VARCHAR(128) NOT NULL,
    expected_deliveries INTEGER NOT NULL,
    missing_dimensions  VARCHAR(2048),
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_detection_route_source_position
    ON t_detection_route_source (source_topic, source_partition, source_offset);
CREATE INDEX IF NOT EXISTS idx_detection_route_source_event
    ON t_detection_route_source (tenant_id, source_event_id);

-- Transactional routing outbox. Kafka publication is retryable and duplicate
-- publication is safe because delivery_id is the downstream journal identity.
CREATE TABLE IF NOT EXISTS t_detection_route_outbox (
    delivery_id         VARCHAR(128) PRIMARY KEY,
    source_key          VARCHAR(512) NOT NULL,
    tenant_id           VARCHAR(64) NOT NULL,
    source_event_id     VARCHAR(128) NOT NULL,
    routing_version     VARCHAR(64) NOT NULL,
    plan_version        VARCHAR(128) NOT NULL,
    delivery_kind       VARCHAR(16) NOT NULL,
    routing_dimension   VARCHAR(128) NOT NULL,
    routing_value       VARCHAR(1024) NOT NULL,
    routing_key         VARCHAR(255) NOT NULL,
    payload             TEXT NOT NULL,
    status              VARCHAR(16) NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at        TIMESTAMP(6) WITH TIME ZONE,
    delivery_topic      VARCHAR(255),
    delivery_partition  INTEGER,
    delivery_offset     BIGINT,
    last_error          VARCHAR(1024)
);
CREATE INDEX IF NOT EXISTS idx_detection_route_outbox_due
    ON t_detection_route_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_detection_route_outbox_source
    ON t_detection_route_outbox (source_key);
CREATE INDEX IF NOT EXISTS idx_detection_route_outbox_published
    ON t_detection_route_outbox (delivery_topic, delivery_partition, delivery_offset);
