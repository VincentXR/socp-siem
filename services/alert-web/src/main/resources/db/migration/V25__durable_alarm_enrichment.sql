ALTER TABLE t_alarm ADD COLUMN initial_risk_score INTEGER;
ALTER TABLE t_alarm ADD COLUMN initial_risk_level VARCHAR(16);
ALTER TABLE t_alarm ADD COLUMN enriched_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE t_alarm ALTER COLUMN ti_hits TYPE TEXT;
-- Legacy alarms lack an immutable admission snapshot; preserve the last known value.
UPDATE t_alarm SET initial_risk_score = risk_score, initial_risk_level = risk_level;
