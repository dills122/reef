CREATE TABLE IF NOT EXISTS settlement.allocations (
  settlement_allocation_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  buy_order_id TEXT NOT NULL,
  sell_order_id TEXT NOT NULL,
  buyer_account_id TEXT NOT NULL,
  seller_account_id TEXT NOT NULL,
  quantity TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state = 'ALLOCATION_PROPOSED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.confirmations (
  settlement_confirmation_id TEXT PRIMARY KEY,
  settlement_allocation_id TEXT NOT NULL,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state = 'CONFIRMATION_GENERATED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.affirmations (
  settlement_affirmation_id TEXT PRIMARY KEY,
  settlement_confirmation_id TEXT NOT NULL,
  settlement_allocation_id TEXT NOT NULL,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  actor_type TEXT NOT NULL CHECK (actor_type = 'SYSTEM'),
  actor_id TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state = 'AFFIRMATION_ACCEPTED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_settlement_allocations_run
  ON settlement.allocations(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_confirmations_run
  ON settlement.confirmations(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_affirmations_run
  ON settlement.affirmations(scenario_run_id, occurred_at);
