CREATE TABLE IF NOT EXISTS admin.post_trade_profiles (
  profile_id TEXT PRIMARY KEY,
  mode TEXT NOT NULL,
  settlement_cycle TEXT NOT NULL,
  netting_mode TEXT NOT NULL,
  ledger_posting_mode TEXT NOT NULL,
  policy_version INTEGER NOT NULL CHECK (policy_version > 0),
  active BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_post_trade_profiles_active_one
  ON admin.post_trade_profiles(active)
  WHERE active;

