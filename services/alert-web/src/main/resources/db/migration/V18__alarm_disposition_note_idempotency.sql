-- Connector retries must not append the same analyst note twice.
ALTER TABLE t_alarm_disposition
    ADD COLUMN IF NOT EXISTS note_keys TEXT;
