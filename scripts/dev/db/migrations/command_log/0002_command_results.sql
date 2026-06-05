-- Result/status fields for command status lookup and captured command modes.

ALTER TABLE command_log.commands
  ADD COLUMN IF NOT EXISTS response_status INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb;
