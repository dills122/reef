CREATE TABLE IF NOT EXISTS settlement.operator_actions (
  settlement_operator_action_id text PRIMARY KEY,
  scenario_run_id text NOT NULL,
  post_trade_profile_id text NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version integer NOT NULL DEFAULT 1,
  correlation_id text NOT NULL,
  causation_id text NOT NULL,
  action text NOT NULL CHECK (action IN ('FORCE_SETTLE', 'REVERSE_LEDGER_ENTRY')),
  target_id text NOT NULL,
  reason_note text NOT NULL,
  actor_type text NOT NULL CHECK (actor_type = 'USER'),
  actor_id text NOT NULL,
  occurred_at timestamptz NOT NULL,
  inserted_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_settlement_operator_actions_run
  ON settlement.operator_actions(scenario_run_id, occurred_at);
