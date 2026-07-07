ALTER TABLE arena.run_bot_results
  ADD COLUMN IF NOT EXISTS score_eligible BOOLEAN NOT NULL DEFAULT true,
  ADD COLUMN IF NOT EXISTS public_leaderboard BOOLEAN NOT NULL DEFAULT true;

DROP INDEX IF EXISTS arena.idx_arena_run_bot_results_leaderboard;

CREATE INDEX IF NOT EXISTS idx_arena_run_bot_results_leaderboard
  ON arena.run_bot_results(scoring_policy_version, score_eligible, public_leaderboard, disqualified, final_equity DESC, realized_pnl DESC, max_drawdown ASC);
