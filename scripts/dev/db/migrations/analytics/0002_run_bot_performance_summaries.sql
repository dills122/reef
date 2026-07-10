CREATE TABLE IF NOT EXISTS analytics.run_bot_performance_summaries (
  run_id TEXT NOT NULL,
  bot_id TEXT NOT NULL,
  scenario_id TEXT NOT NULL DEFAULT '',
  profile TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT '',
  completed_at TIMESTAMPTZ,
  exported_at TIMESTAMPTZ NOT NULL,
  projected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  final_equity DOUBLE PRECISION,
  realized_pnl DOUBLE PRECISION,
  max_drawdown DOUBLE PRECISION,
  fail_count BIGINT NOT NULL DEFAULT 0,
  command_count BIGINT NOT NULL DEFAULT 0,
  settlement_score_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  source_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, bot_id)
);

CREATE INDEX IF NOT EXISTS idx_analytics_bot_perf_recent
ON analytics.run_bot_performance_summaries(completed_at DESC, exported_at DESC, bot_id);

CREATE INDEX IF NOT EXISTS idx_analytics_bot_perf_bot_recent
ON analytics.run_bot_performance_summaries(bot_id, completed_at DESC, exported_at DESC);
