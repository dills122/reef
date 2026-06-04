-- Bring command capture migration shape in line with PostgresCommandCaptureStore.

ALTER TABLE boundary.api_command_captures
  ADD COLUMN IF NOT EXISTS correlation_id TEXT NOT NULL DEFAULT '';

ALTER TABLE boundary.api_command_captures
  ADD COLUMN IF NOT EXISTS first_received_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE boundary.api_command_captures
  ALTER COLUMN response_status SET DEFAULT 0;

ALTER TABLE boundary.api_command_captures
  ALTER COLUMN response_payload SET DEFAULT '';

ALTER TABLE boundary.api_command_captures
  ALTER COLUMN error_class SET DEFAULT '';

ALTER TABLE boundary.api_command_captures
  ALTER COLUMN error_message SET DEFAULT '';

ALTER TABLE boundary.api_command_captures
  ALTER COLUMN last_updated_at TYPE TIMESTAMPTZ USING last_updated_at::TIMESTAMPTZ,
  ALTER COLUMN last_updated_at SET DEFAULT NOW();
