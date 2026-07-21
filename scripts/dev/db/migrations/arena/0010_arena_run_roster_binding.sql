ALTER TABLE arena.run_records
  ADD COLUMN IF NOT EXISTS admission_window_id TEXT NOT NULL DEFAULT 'legacy-unbound',
  ADD COLUMN IF NOT EXISTS roster_snapshot_id TEXT NOT NULL DEFAULT 'legacy-unbound',
  ADD COLUMN IF NOT EXISTS roster_snapshot_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
  ADD COLUMN IF NOT EXISTS seed_set_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
  ADD COLUMN IF NOT EXISTS actor_profile_version TEXT NOT NULL DEFAULT 'legacy-unbound',
  ADD COLUMN IF NOT EXISTS actor_profile_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
  ADD COLUMN IF NOT EXISTS risk_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000';

ALTER TABLE arena.run_records
  ADD CONSTRAINT chk_arena_run_records_roster_snapshot_hash
    CHECK (roster_snapshot_hash ~ '^sha256:[a-f0-9]{64}$'),
  ADD CONSTRAINT chk_arena_run_records_seed_set_hash
    CHECK (seed_set_hash ~ '^sha256:[a-f0-9]{64}$'),
  ADD CONSTRAINT chk_arena_run_records_actor_profile_hash
    CHECK (actor_profile_hash ~ '^sha256:[a-f0-9]{64}$'),
  ADD CONSTRAINT chk_arena_run_records_risk_policy_hash
    CHECK (risk_policy_hash ~ '^sha256:[a-f0-9]{64}$');

CREATE INDEX IF NOT EXISTS idx_arena_run_records_roster_binding
  ON arena.run_records(admission_window_id, roster_snapshot_id, roster_snapshot_hash);
