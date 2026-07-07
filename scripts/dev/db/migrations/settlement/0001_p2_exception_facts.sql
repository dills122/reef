-- Minimal P2 settlement exception facts.
-- Scope: trade -> obligation -> cash-leg break -> repair -> resolved.

CREATE SCHEMA IF NOT EXISTS settlement;

CREATE TABLE IF NOT EXISTS settlement.obligations (
  settlement_obligation_id TEXT PRIMARY KEY,
  scenario_run_id TEXT NOT NULL,
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

CREATE TABLE IF NOT EXISTS settlement.breaks (
  settlement_break_id TEXT PRIMARY KEY,
  settlement_obligation_id TEXT NOT NULL,
  scenario_run_id TEXT NOT NULL,
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
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  settlement_state TEXT NOT NULL CHECK (settlement_state = 'RESOLVED'),
  exception_state TEXT NOT NULL CHECK (exception_state = 'RESOLVED'),
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_settlement_obligations_run ON settlement.obligations(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_breaks_run ON settlement.breaks(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_repairs_run ON settlement.repairs(scenario_run_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_settlement_resolutions_run ON settlement.resolutions(scenario_run_id, occurred_at);
