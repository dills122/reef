-- Split mutable worker state and terminal responses out of immutable command intake.

CREATE TABLE IF NOT EXISTS command_log.command_work_queue (
  command_id TEXT PRIMARY KEY REFERENCES command_log.commands(command_id) ON DELETE CASCADE,
  status TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  leased_by TEXT NOT NULL DEFAULT '',
  leased_until TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_command_log_work_queue_status_updated
  ON command_log.command_work_queue(status, updated_at, command_id);

CREATE TABLE IF NOT EXISTS command_log.command_results (
  command_id TEXT PRIMARY KEY REFERENCES command_log.commands(command_id) ON DELETE CASCADE,
  response_status INTEGER NOT NULL,
  response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO command_log.command_work_queue(command_id, status, attempt_count, last_error, updated_at)
SELECT command_id, status, attempt_count, last_error, created_at
FROM command_log.commands
ON CONFLICT (command_id) DO NOTHING;

INSERT INTO command_log.command_results(command_id, response_status, response_payload_json, completed_at)
SELECT command_id, response_status, response_payload_json, created_at
FROM command_log.commands
WHERE status IN ('COMPLETED', 'FAILED') OR response_status > 0
ON CONFLICT (command_id) DO NOTHING;
