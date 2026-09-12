-- Record the owner fencing epoch that produced each partition entry in a
-- checkpoint vector. The owner row is locked and checked before a generation
-- is saved, so a stale instance cannot overwrite a new owner's snapshot.
ALTER TABLE t_detection_state_snapshot
    ADD COLUMN IF NOT EXISTS partition_owner_epochs_json TEXT NOT NULL DEFAULT '{}';

UPDATE t_detection_state_snapshot
SET partition_owner_epochs_json = '{}'
WHERE partition_owner_epochs_json IS NULL;
