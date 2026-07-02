-- Retain named command-log history across local prune runs.

CREATE TABLE IF NOT EXISTS command_log.retention_pins (
  pin_id TEXT PRIMARY KEY,
  selector_type TEXT NOT NULL,
  selector_value TEXT NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (selector_type, selector_value),
  CHECK (selector_type IN ('command_id', 'idempotency_prefix', 'trace_id', 'correlation_id', 'client_id'))
);

CREATE INDEX IF NOT EXISTS idx_command_log_retention_pins_selector
ON command_log.retention_pins(selector_type, selector_value);
