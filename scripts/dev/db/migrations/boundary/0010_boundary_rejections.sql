CREATE SCHEMA IF NOT EXISTS boundary;

CREATE TABLE IF NOT EXISTS boundary.boundary_rejections (
  rejection_id TEXT NOT NULL PRIMARY KEY,
  rejected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  guardrail_type TEXT NOT NULL,
  scope_type TEXT NOT NULL DEFAULT '',
  scope_id TEXT NOT NULL DEFAULT '',
  status INTEGER NOT NULL,
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
  quantity_units TEXT NOT NULL DEFAULT '',
  limit_price TEXT NOT NULL DEFAULT '',
  currency TEXT NOT NULL DEFAULT '',
  payload_hash TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_boundary_rejections_guardrail_time
  ON boundary.boundary_rejections(guardrail_type, rejected_at DESC);
