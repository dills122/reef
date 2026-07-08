CREATE TABLE IF NOT EXISTS settlement.leg_outcomes (
  settlement_leg_outcome_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  settlement_instruction_id TEXT NOT NULL,
  settlement_attempt_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  leg_type TEXT NOT NULL CHECK (leg_type IN ('CASH', 'SECURITY')),
  state TEXT NOT NULL CHECK (state = 'LEG_SUCCEEDED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.ledger_entries (
  ledger_entry_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  settlement_instruction_id TEXT NOT NULL,
  settlement_attempt_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
  asset_id TEXT NOT NULL,
  direction TEXT NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
  quantity TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.settlements (
  settlement_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  settlement_instruction_id TEXT NOT NULL,
  settlement_attempt_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  settlement_state TEXT NOT NULL CHECK (settlement_state = 'SETTLED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_settlement_leg_outcomes_run
  ON settlement.leg_outcomes(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_ledger_entries_run
  ON settlement.ledger_entries(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_settlements_run
  ON settlement.settlements(scenario_run_id, occurred_at);

