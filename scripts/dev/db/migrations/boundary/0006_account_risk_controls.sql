-- Account/bot protective controls run before durable command acceptance.

CREATE TABLE IF NOT EXISTS boundary.account_risk_controls (
  scope_type TEXT NOT NULL,
  scope_id TEXT NOT NULL,
  decision TEXT NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (scope_type, scope_id),
  CHECK (scope_type IN ('ACCOUNT', 'BOT')),
  CHECK (decision IN ('ALLOW', 'REJECT', 'BACKPRESSURE', 'DISABLED_BOT'))
);

CREATE TABLE IF NOT EXISTS boundary.account_risk_decisions (
  decision_id TEXT NOT NULL PRIMARY KEY,
  decided_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  decision TEXT NOT NULL,
  code TEXT NOT NULL,
  message TEXT NOT NULL,
  client_id TEXT NOT NULL,
  route TEXT NOT NULL,
  command_type TEXT NOT NULL,
  command_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  bot_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  CHECK (decision IN ('REJECT', 'BACKPRESSURE', 'DISABLED_BOT'))
);

CREATE INDEX IF NOT EXISTS idx_account_risk_decisions_scope_time
  ON boundary.account_risk_decisions(account_id, bot_id, decided_at DESC);
