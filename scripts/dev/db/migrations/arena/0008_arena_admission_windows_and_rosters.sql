CREATE TABLE IF NOT EXISTS arena.admission_windows (
  window_id TEXT PRIMARY KEY,
  policy_version TEXT NOT NULL,
  scheduled_start TIMESTAMPTZ NOT NULL,
  invite_decision_cutoff TIMESTAMPTZ NOT NULL,
  merge_readiness_cutoff TIMESTAMPTZ NOT NULL,
  roster_lock_at TIMESTAMPTZ NOT NULL,
  operational_recheck_at TIMESTAMPTZ NOT NULL,
  run_instantiation_at TIMESTAMPTZ NOT NULL,
  display_time_zone TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CHECK (invite_decision_cutoff < merge_readiness_cutoff),
  CHECK (merge_readiness_cutoff < roster_lock_at),
  CHECK (roster_lock_at < operational_recheck_at),
  CHECK (operational_recheck_at < run_instantiation_at),
  CHECK (run_instantiation_at < scheduled_start),
  CHECK (created_at <= invite_decision_cutoff)
);

CREATE TABLE IF NOT EXISTS arena.eligibility_decisions (
  evaluation_id TEXT PRIMARY KEY,
  window_id TEXT NOT NULL REFERENCES arena.admission_windows(window_id),
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  outcome TEXT NOT NULL CHECK (outcome IN ('eligible_for_roster', 'rolled_to_next_window', 'excluded')),
  source_hash TEXT NOT NULL,
  artifact_hash TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  evaluated_at TIMESTAMPTZ NOT NULL,
  correlation_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS arena.eligibility_decision_reasons (
  evaluation_id TEXT NOT NULL REFERENCES arena.eligibility_decisions(evaluation_id),
  reason_order INTEGER NOT NULL,
  reason_code TEXT NOT NULL,
  PRIMARY KEY (evaluation_id, reason_order),
  UNIQUE (evaluation_id, reason_code)
);

CREATE TABLE IF NOT EXISTS arena.roster_snapshots (
  snapshot_id TEXT PRIMARY KEY,
  window_id TEXT NOT NULL UNIQUE REFERENCES arena.admission_windows(window_id),
  mode_id TEXT NOT NULL,
  scenario_id TEXT NOT NULL,
  seed_set_hash TEXT NOT NULL,
  actor_profile_version TEXT NOT NULL,
  actor_profile_hash TEXT NOT NULL,
  risk_policy_version TEXT NOT NULL,
  risk_policy_hash TEXT NOT NULL,
  scoring_policy_version TEXT NOT NULL,
  scoring_policy_hash TEXT NOT NULL,
  economic_policy_version TEXT NOT NULL,
  economic_policy_hash TEXT NOT NULL,
  snapshot_hash TEXT NOT NULL,
  max_bots INTEGER NOT NULL CHECK (max_bots > 0),
  locked_at TIMESTAMPTZ NOT NULL,
  locked_by TEXT NOT NULL,
  correlation_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS arena.roster_snapshot_entries (
  snapshot_id TEXT NOT NULL REFERENCES arena.roster_snapshots(snapshot_id),
  bot_order INTEGER NOT NULL CHECK (bot_order >= 0),
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  priority INTEGER NOT NULL CHECK (priority >= 0),
  source_hash TEXT NOT NULL,
  artifact_hash TEXT NOT NULL,
  config_hash TEXT NOT NULL,
  eligibility_evaluation_id TEXT NOT NULL REFERENCES arena.eligibility_decisions(evaluation_id),
  PRIMARY KEY (snapshot_id, bot_order),
  UNIQUE (snapshot_id, bot_id, version_id)
);

CREATE TABLE IF NOT EXISTS arena.roster_removals (
  removal_id TEXT PRIMARY KEY,
  window_id TEXT NOT NULL REFERENCES arena.admission_windows(window_id),
  snapshot_id TEXT NOT NULL REFERENCES arena.roster_snapshots(snapshot_id),
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  reason_code TEXT NOT NULL CHECK (reason_code IN ('security', 'trust', 'config', 'availability')),
  detail TEXT NOT NULL,
  removed_at TIMESTAMPTZ NOT NULL,
  removed_by TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  UNIQUE (snapshot_id, bot_id, version_id),
  FOREIGN KEY (snapshot_id, bot_id, version_id)
    REFERENCES arena.roster_snapshot_entries(snapshot_id, bot_id, version_id)
);

CREATE INDEX IF NOT EXISTS idx_arena_eligibility_decisions_window
  ON arena.eligibility_decisions(window_id, evaluated_at, bot_id, version_id);

CREATE INDEX IF NOT EXISTS idx_arena_roster_entries_bot_version
  ON arena.roster_snapshot_entries(bot_id, version_id);

CREATE INDEX IF NOT EXISTS idx_arena_roster_removals_window
  ON arena.roster_removals(window_id, removed_at, bot_id, version_id);
