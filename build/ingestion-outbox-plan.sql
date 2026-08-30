-- PostgreSQL-only, read-only plan check for the bounded ingestion outbox poll.
-- Run against the search-config database after representative data is loaded:
--   psql "$SOCP_SEARCH_PG_DSN" -f build/ingestion-outbox-plan.sql
-- The migration V6 index is intentionally aligned with this predicate/order.
-- Keep ANALYZE enabled so the result includes actual rows and buffer usage.
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT *
  FROM t_ingestion_outbox
 WHERE status = 'PENDING'
   AND next_attempt_at <= CURRENT_TIMESTAMP
 ORDER BY next_attempt_at ASC, created_at ASC
 LIMIT 200;

-- Confirm the intended index is available to the planner without changing
-- data. A partial-index recommendation should only be adopted after comparing
-- this plan with the production cardinality and write rate.
SELECT indexname, indexdef
  FROM pg_indexes
 WHERE tablename = 't_ingestion_outbox'
 ORDER BY indexname;
