-- Add durable pre-trade account/bot risk limits and decision audit context.

ALTER TABLE boundary.account_risk_controls
  ADD COLUMN IF NOT EXISTS max_quantity_units TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS max_notional TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT '';

ALTER TABLE boundary.account_risk_decisions
  ADD COLUMN IF NOT EXISTS quantity_units TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS limit_price TEXT NOT NULL DEFAULT '',
  ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT '';
