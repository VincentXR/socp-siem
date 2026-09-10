-- Prevent two detection instances from overwriting a newer checkpoint with
-- an older one when they briefly overlap during a rebalance or rollout.
ALTER TABLE t_detection_state_snapshot
    ADD COLUMN IF NOT EXISTS row_version BIGINT NOT NULL DEFAULT 0;
