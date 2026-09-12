-- Canonical events are bounded at the ingest boundary, but a valid event can
-- still exceed the old VARCHAR columns when a collector carries a large
-- message or a wide set of normalized fields. Keep PostgreSQL facts lossless;
-- OpenSearch remains the searchable projection and Kafka remains replayable.
ALTER TABLE t_search_event ALTER COLUMN msg SET DATA TYPE TEXT;
ALTER TABLE t_search_event ALTER COLUMN fields_json SET DATA TYPE TEXT;
ALTER TABLE t_search_event ALTER COLUMN ecs_json SET DATA TYPE TEXT;
