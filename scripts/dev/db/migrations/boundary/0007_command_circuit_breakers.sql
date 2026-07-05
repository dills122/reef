-- Operator-controlled hard circuit breakers for command intake.

CREATE TABLE IF NOT EXISTS boundary.command_circuit_breakers (
  scope_type TEXT NOT NULL,
  scope_id TEXT NOT NULL,
  tripped BOOLEAN NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (scope_type, scope_id),
  CHECK (scope_type IN ('GLOBAL', 'VENUE_SESSION', 'INSTRUMENT'))
);
