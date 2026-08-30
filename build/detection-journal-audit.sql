-- Read-only historical journal audit. Run before any cleanup on a production
-- database and retain the output with the migration/change record.
SELECT status, COUNT(*) AS rows,
       MIN(occurred_at) AS oldest_occurred_at,
       MAX(occurred_at) AS newest_occurred_at,
       MIN(completed_at) AS oldest_completed_at,
       MIN(dead_lettered_at) AS oldest_dead_lettered_at
  FROM t_detection_event
 GROUP BY status
 ORDER BY status;

SELECT
    COUNT(*) FILTER (WHERE status = 'COMPLETED' AND completed_at IS NULL)
        AS completed_without_timestamp,
    COUNT(*) FILTER (WHERE status = 'DEAD_LETTERED' AND dead_lettered_at IS NULL)
        AS dead_lettered_without_timestamp,
    COUNT(*) FILTER (WHERE status = 'PENDING'
                     AND (completed_at IS NOT NULL OR dead_lettered_at IS NOT NULL))
        AS pending_with_terminal_timestamp,
    COUNT(*) FILTER (WHERE status NOT IN ('PENDING', 'COMPLETED', 'DEAD_LETTERED'))
        AS unknown_status
  FROM t_detection_event;

SELECT tenant_id, source_event_id, COUNT(*) AS duplicate_rows
  FROM t_detection_event
 GROUP BY tenant_id, source_event_id
HAVING COUNT(*) > 1
 ORDER BY duplicate_rows DESC, tenant_id, source_event_id;

SELECT event_id, COUNT(*) AS duplicate_storage_rows
  FROM t_detection_event
 GROUP BY event_id
HAVING COUNT(*) > 1
 ORDER BY duplicate_storage_rows DESC, event_id;

-- No automatic DELETE is included here. If the duplicate queries return rows,
-- choose an evidence-preserving survivor (prefer the newest lifecycle update),
-- export the rejected rows, and perform a separately reviewed, tenant-scoped
-- cleanup before adding or validating a uniqueness constraint.
