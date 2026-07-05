CREATE TABLE IF NOT EXISTS arena.run_bot_results (
  run_id TEXT NOT NULL,
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  scoring_policy_version TEXT NOT NULL,
  final_equity BIGINT NOT NULL,
  realized_pnl BIGINT NOT NULL,
  max_drawdown BIGINT NOT NULL,
  actions_proposed INTEGER NOT NULL,
  order_actions_proposed INTEGER NOT NULL,
  data_calls INTEGER NOT NULL,
  signals_generated INTEGER NOT NULL,
  disqualified BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (run_id, bot_id, version_id, scoring_policy_version),
  FOREIGN KEY (run_id) REFERENCES arena.run_records(run_id),
  FOREIGN KEY (bot_id, version_id) REFERENCES arena.bot_versions(bot_id, version_id)
);

CREATE INDEX IF NOT EXISTS idx_arena_run_bot_results_leaderboard
  ON arena.run_bot_results(scoring_policy_version, disqualified, final_equity DESC, realized_pnl DESC, max_drawdown ASC);
