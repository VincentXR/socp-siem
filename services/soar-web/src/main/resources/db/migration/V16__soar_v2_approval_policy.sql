-- Persist the immutable role/group allow-list used by each approval gate.
-- The value is copied from the published definition and contains no secret
-- material.  NULL keeps V7/V13 legacy approval rows compatible; an absent
-- policy means the existing soar:approve permission plus self-approval guard
-- remains the complete policy.
ALTER TABLE t_soar_approval ADD COLUMN IF NOT EXISTS policy_json TEXT;
