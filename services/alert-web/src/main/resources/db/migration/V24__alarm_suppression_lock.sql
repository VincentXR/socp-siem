-- The tenant row serializes quota checks and same-scope updates in the caller's
-- transaction, without a REQUIRES_NEW connection or process-local coordination.
CREATE TABLE IF NOT EXISTS t_alarm_suppression_lock (
    tenant_id VARCHAR(255) NOT NULL PRIMARY KEY
);
