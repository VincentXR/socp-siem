-- Preserve the event identity and canonical ECS fields in the local search fallback.
ALTER TABLE t_search_event ADD COLUMN IF NOT EXISTS event_id VARCHAR(255);
ALTER TABLE t_search_event ADD COLUMN IF NOT EXISTS ecs_json VARCHAR(4000);

CREATE INDEX IF NOT EXISTS idx_t_search_event_event_id ON t_search_event (event_id);
