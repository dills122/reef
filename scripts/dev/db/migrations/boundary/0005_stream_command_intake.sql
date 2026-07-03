CREATE TABLE IF NOT EXISTS boundary.stream_command_intake (
  scope TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  command_id TEXT NOT NULL,
  route TEXT NOT NULL,
  subject TEXT NOT NULL,
  stream_name TEXT NOT NULL,
  partition INTEGER NOT NULL,
  stream_sequence BIGINT NOT NULL DEFAULT 0,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at TIMESTAMPTZ,
  PRIMARY KEY (scope, idempotency_key),
  UNIQUE (command_id)
);

CREATE INDEX IF NOT EXISTS idx_stream_command_intake_command_id
  ON boundary.stream_command_intake(command_id);
