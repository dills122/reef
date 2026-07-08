CREATE TABLE IF NOT EXISTS runtime.reference_scenario_runs (
  scenario_run_id TEXT PRIMARY KEY,
  post_trade_profile_id TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS runtime.reference_venue_sessions (
  venue_session_id TEXT PRIMARY KEY,
  post_trade_profile_id TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

