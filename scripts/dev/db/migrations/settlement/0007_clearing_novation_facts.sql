CREATE TABLE IF NOT EXISTS settlement.clearing_submissions (
  settlement_clearing_submission_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  settlement_affirmation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state = 'CLEARING_SUBMITTED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.clearing_acceptances (
  settlement_clearing_acceptance_id TEXT PRIMARY KEY,
  settlement_clearing_submission_id TEXT NOT NULL,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state = 'CLEARING_ACCEPTED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.clearing_rejections (
  settlement_clearing_rejection_id TEXT PRIMARY KEY,
  settlement_clearing_submission_id TEXT NOT NULL,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  reason TEXT NOT NULL CHECK (reason = 'CLEARING_REJECT'),
  state TEXT NOT NULL CHECK (state = 'CLEARING_REJECTED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.novations (
  settlement_novation_id TEXT PRIMARY KEY,
  settlement_clearing_acceptance_id TEXT NOT NULL,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state = 'NOVATION_RECORDED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_settlement_clearing_submissions_run
  ON settlement.clearing_submissions(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_clearing_acceptances_run
  ON settlement.clearing_acceptances(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_clearing_rejections_run
  ON settlement.clearing_rejections(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_novations_run
  ON settlement.novations(scenario_run_id, occurred_at);
