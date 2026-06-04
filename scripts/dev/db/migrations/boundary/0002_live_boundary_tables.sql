-- Live API boundary tables used by idempotency and command capture stores.

CREATE SCHEMA IF NOT EXISTS boundary;

CREATE TABLE IF NOT EXISTS boundary.api_idempotency_records (
  client_id TEXT NOT NULL,
  route TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  status TEXT NOT NULL,
  payload TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (client_id, route, idempotency_key)
);

CREATE TABLE IF NOT EXISTS boundary.api_command_captures (
  client_id TEXT NOT NULL,
  route TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  command_id TEXT NOT NULL,
  request_payload TEXT NOT NULL,
  status TEXT NOT NULL,
  response_status INT NOT NULL,
  response_payload TEXT NOT NULL,
  error_class TEXT NOT NULL,
  error_message TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_updated_at TEXT NOT NULL,
  PRIMARY KEY (client_id, route, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_api_command_captures_status_updated
  ON boundary.api_command_captures(status, last_updated_at DESC);
