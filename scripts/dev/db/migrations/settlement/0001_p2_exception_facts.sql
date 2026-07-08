-- Minimal P2 settlement exception facts.
-- Scope: trade -> obligation -> cash-leg break -> repair -> resolved.

CREATE SCHEMA IF NOT EXISTS settlement;

CREATE TABLE IF NOT EXISTS settlement.obligations (
  settlement_obligation_id TEXT PRIMARY KEY,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  buyer_participant_id TEXT NOT NULL,
  seller_participant_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity TEXT NOT NULL,
  cash_amount TEXT NOT NULL,
  currency TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state = 'OBLIGATION_CREATED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.instructions (
  settlement_instruction_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  instruction_type TEXT NOT NULL CHECK (instruction_type = 'DVP'),
  state TEXT NOT NULL CHECK (state = 'INSTRUCTION_CREATED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.attempts (
  settlement_attempt_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  settlement_instruction_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
  state TEXT NOT NULL CHECK (state = 'ATTEMPT_STARTED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.breaks (
  settlement_break_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  reason TEXT NOT NULL CHECK (reason = 'CASH_LEG_FAILED'),
  state TEXT NOT NULL CHECK (state = 'BROKEN'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.repairs (
  settlement_repair_id TEXT PRIMARY KEY,
  settlement_break_id TEXT NOT NULL,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  repair_action TEXT NOT NULL CHECK (repair_action = 'POST_CASH_LEG_REPAIR'),
  actor_type TEXT NOT NULL CHECK (actor_type = 'USER'),
  actor_id TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS settlement.resolutions (
  settlement_resolution_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  settlement_break_id TEXT NOT NULL,
  settlement_repair_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  settlement_state TEXT NOT NULL CHECK (settlement_state = 'RESOLVED'),
  exception_state TEXT NOT NULL CHECK (exception_state = 'RESOLVED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE settlement.obligations
  ADD COLUMN IF NOT EXISTS post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1';
ALTER TABLE settlement.obligations
  ADD COLUMN IF NOT EXISTS post_trade_policy_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE settlement.instructions
  ADD COLUMN IF NOT EXISTS post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1';
ALTER TABLE settlement.instructions
  ADD COLUMN IF NOT EXISTS post_trade_policy_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE settlement.attempts
  ADD COLUMN IF NOT EXISTS settlement_instruction_id TEXT NOT NULL DEFAULT '';
ALTER TABLE settlement.attempts
  ADD COLUMN IF NOT EXISTS post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1';
ALTER TABLE settlement.attempts
  ADD COLUMN IF NOT EXISTS post_trade_policy_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE settlement.breaks
  ADD COLUMN IF NOT EXISTS post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1';
ALTER TABLE settlement.breaks
  ADD COLUMN IF NOT EXISTS post_trade_policy_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE settlement.repairs
  ADD COLUMN IF NOT EXISTS post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1';
ALTER TABLE settlement.repairs
  ADD COLUMN IF NOT EXISTS post_trade_policy_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE settlement.resolutions
  ADD COLUMN IF NOT EXISTS post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1';
ALTER TABLE settlement.resolutions
  ADD COLUMN IF NOT EXISTS post_trade_policy_version INTEGER NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_settlement_obligations_run ON settlement.obligations(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_instructions_run ON settlement.instructions(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_attempts_run ON settlement.attempts(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_breaks_run ON settlement.breaks(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_repairs_run ON settlement.repairs(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_resolutions_run ON settlement.resolutions(scenario_run_id, occurred_at);
