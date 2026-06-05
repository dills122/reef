-- Append-only durable command intake slice.

CREATE SCHEMA IF NOT EXISTS command_log;

CREATE TABLE IF NOT EXISTS command_log.commands (
  command_id TEXT PRIMARY KEY,
  client_id TEXT NOT NULL,
  route TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  trace_id TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  command_type TEXT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  payload_json JSONB NOT NULL,
  status TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (client_id, route, idempotency_key),
  CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_command_log_commands_status_received
  ON command_log.commands(status, received_at);
