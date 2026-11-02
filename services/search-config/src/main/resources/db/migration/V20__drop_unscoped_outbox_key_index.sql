-- V18's state/key/order index covers predecessor and member probes. Retaining
-- V15's shorter key/sequence index lets stale all-PUBLISHED statistics choose
-- repeated history scans with status applied only as a heap filter.
-- Preserve every event and lease; only remove the superseded access path.
DROP INDEX IF EXISTS idx_ingestion_outbox_key_sequence;
