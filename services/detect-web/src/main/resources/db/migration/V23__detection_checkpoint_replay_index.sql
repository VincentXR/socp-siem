-- Composite index for Kafka-position-ordered Detection journal scans.
--
-- review 2026-09 (data-layer Medium): the checkpoint-vector replay and the
-- partition-bounded recent/PENDING scans filter on (tenant_id, status) and order
-- by (kafka_partition, kafka_offset), but nothing up to V21 matched both the
-- predicate and that ordering. V12 covered (tenant_id, status, completed_at) and
-- V5 (status, kafka_partition, kafka_offset) crosses tenants, so the planner had
-- to fetch by one index and sort by the other. This composite index lets the
-- tenant-scoped vector scan and the PENDING/recent partition-ordered replays be
-- served in index order, cutting recovery sort cost on the hot journal table.
--
-- Plain CREATE INDEX only: the in-Pod transactional Flyway runner cannot execute
-- CONCURRENTLY inside its migration transaction. t_detection_event is the hot
-- table, so the build takes a brief write lock -- see
-- docs/detection-state-sharding.md for the maintenance window and the recommended
-- lock_timeout default for the migration datasource.
CREATE INDEX IF NOT EXISTS idx_detection_event_checkpoint_replay
    ON t_detection_event (tenant_id, status, kafka_partition, kafka_offset);
