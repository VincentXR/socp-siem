-- Store bounded JSON artifacts in the database when an object-store adapter
-- is not configured. The storage_ref remains opaque to callers.
ALTER TABLE t_soar_artifact ADD COLUMN IF NOT EXISTS inline_json TEXT;
