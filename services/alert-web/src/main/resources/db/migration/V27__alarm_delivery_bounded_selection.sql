-- Keep retry selection and lifecycle maintenance off retained payload pages.
CREATE INDEX idx_alarm_delivery_due_covering
    ON alarm_delivery (status, next_attempt_at, created_at, id);
DROP INDEX idx_alarm_delivery_due;
ALTER INDEX idx_alarm_delivery_due_covering RENAME TO idx_alarm_delivery_due;

CREATE INDEX idx_alarm_delivery_lifecycle
    ON alarm_delivery (status, attempts, updated_at, id, claimed_at);
