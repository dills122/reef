ALTER TABLE arena.run_records
  ADD COLUMN IF NOT EXISTS policy_envelope_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
  ADD COLUMN IF NOT EXISTS scoring_policy_version TEXT NOT NULL DEFAULT 'legacy-unresolved',
  ADD COLUMN IF NOT EXISTS scoring_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
  ADD COLUMN IF NOT EXISTS economic_policy_version TEXT NOT NULL DEFAULT 'legacy-unresolved',
  ADD COLUMN IF NOT EXISTS economic_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000';

ALTER TABLE arena.run_bot_results
  ADD COLUMN IF NOT EXISTS scoring_policy_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000',
  ADD COLUMN IF NOT EXISTS policy_envelope_hash TEXT NOT NULL DEFAULT 'sha256:0000000000000000000000000000000000000000000000000000000000000000';

ALTER TABLE arena.run_records
  ADD CONSTRAINT chk_arena_run_records_policy_envelope_hash
    CHECK (policy_envelope_hash ~ '^sha256:[a-f0-9]{64}$'),
  ADD CONSTRAINT chk_arena_run_records_scoring_policy_hash
    CHECK (scoring_policy_hash ~ '^sha256:[a-f0-9]{64}$'),
  ADD CONSTRAINT chk_arena_run_records_economic_policy_hash
    CHECK (economic_policy_hash ~ '^sha256:[a-f0-9]{64}$');

ALTER TABLE arena.run_bot_results
  ADD CONSTRAINT chk_arena_run_bot_results_scoring_policy_hash
    CHECK (scoring_policy_hash ~ '^sha256:[a-f0-9]{64}$'),
  ADD CONSTRAINT chk_arena_run_bot_results_policy_envelope_hash
    CHECK (policy_envelope_hash ~ '^sha256:[a-f0-9]{64}$');

CREATE INDEX IF NOT EXISTS idx_arena_run_records_policy_lock
  ON arena.run_records(scoring_policy_version, scoring_policy_hash, economic_policy_version, economic_policy_hash);
