CREATE TABLE IF NOT EXISTS settlement.resource_positions (
  resource_position_id TEXT PRIMARY KEY,
  scenario_run_id TEXT NOT NULL,
  post_trade_profile_id TEXT NOT NULL DEFAULT 'ops-realistic-v1',
  post_trade_policy_version INTEGER NOT NULL DEFAULT 1,
  correlation_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  asset_type TEXT NOT NULL CHECK (asset_type IN ('CASH', 'SECURITY')),
  asset_id TEXT NOT NULL,
  quantity TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_settlement_resource_positions_run
  ON settlement.resource_positions(scenario_run_id, occurred_at);

DO $$
DECLARE
  constraint_name text;
BEGIN
  SELECT conname INTO constraint_name
  FROM pg_constraint
  WHERE conrelid = 'settlement.leg_outcomes'::regclass
    AND contype = 'c'
    AND pg_get_constraintdef(oid) LIKE '%LEG_SUCCEEDED%';

  IF constraint_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE settlement.leg_outcomes DROP CONSTRAINT %I', constraint_name);
  END IF;
END $$;

ALTER TABLE settlement.leg_outcomes
  ADD CONSTRAINT leg_outcomes_state_check
  CHECK (state IN ('LEG_SUCCEEDED', 'LEG_FAILED')) NOT VALID;

ALTER TABLE settlement.leg_outcomes
  VALIDATE CONSTRAINT leg_outcomes_state_check;

DO $$
DECLARE
  constraint_name text;
BEGIN
  SELECT conname INTO constraint_name
  FROM pg_constraint
  WHERE conrelid = 'settlement.breaks'::regclass
    AND contype = 'c'
    AND pg_get_constraintdef(oid) LIKE '%CASH_LEG_FAILED%';

  IF constraint_name IS NOT NULL THEN
    EXECUTE format('ALTER TABLE settlement.breaks DROP CONSTRAINT %I', constraint_name);
  END IF;
END $$;

ALTER TABLE settlement.breaks
  ADD CONSTRAINT breaks_reason_check
  CHECK (reason IN ('CASH_LEG_FAILED', 'SECURITY_LEG_FAILED')) NOT VALID;

ALTER TABLE settlement.breaks
  VALIDATE CONSTRAINT breaks_reason_check;
