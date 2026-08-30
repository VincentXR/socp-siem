-- Keep terminal journal cleanup index-backed and preserve failure evidence
-- independently from the shorter state-replay retention window.
CREATE INDEX IF NOT EXISTS idx_detection_event_completed_retention
    ON t_detection_event (status, completed_at);
CREATE INDEX IF NOT EXISTS idx_detection_event_dead_letter_retention
    ON t_detection_event (status, dead_lettered_at);
