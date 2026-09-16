-- Trace context for the Detection -> Alert Web hand-off.
--
-- The outbox publisher runs on a scheduler thread that never saw the record
-- being processed, so it has no live span to inherit and nothing to inject.
-- Persisting the W3C traceparent at enqueue time, while the consuming span is
-- still current, is what lets the later publish rejoin the same trace instead
-- of starting a second one that merely prints the same trace id.
ALTER TABLE t_detection_alert_outbox
    ADD COLUMN IF NOT EXISTS traceparent VARCHAR(255);
