-- Bound the PostgreSQL fallback/idempotency copy by ingestion time.
-- Cleanup walks oldest rows first; (created_at, id) avoids a full-table sort.
CREATE INDEX IF NOT EXISTS idx_search_event_retention
    ON t_search_event (created_at, id);
