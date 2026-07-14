ALTER TABLE boundary.stream_command_intake
  ADD COLUMN IF NOT EXISTS client_id TEXT NOT NULL DEFAULT '';

ALTER TABLE boundary.stream_command_intake
  ADD COLUMN IF NOT EXISTS participant_id TEXT NOT NULL DEFAULT '';
