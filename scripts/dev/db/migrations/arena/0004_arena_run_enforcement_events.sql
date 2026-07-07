CREATE TABLE IF NOT EXISTS arena.run_enforcement_events (
  run_id TEXT NOT NULL,
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  decision TEXT NOT NULL,
  reason_code TEXT NOT NULL,
  reason TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  counters_json TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (run_id, bot_id, version_id, decision, reason_code),
  FOREIGN KEY (run_id) REFERENCES arena.run_records(run_id),
  FOREIGN KEY (bot_id, version_id) REFERENCES arena.bot_versions(bot_id, version_id)
);

CREATE INDEX IF NOT EXISTS idx_arena_run_enforcement_events_run
  ON arena.run_enforcement_events(run_id, occurred_at DESC);
