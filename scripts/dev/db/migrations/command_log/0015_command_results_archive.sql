-- Partitioned archive target for terminal command results.
--
-- command_results stays the hot lookup table for recent terminal outcomes.
-- Older, unpinned terminal rows can be copied here by a later archive job and
-- dropped by completed_at partitions instead of table-wide deletes.

CREATE TABLE IF NOT EXISTS command_log.command_results_archive (
  command_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'COMPLETED',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  response_status INTEGER NOT NULL,
  response_payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  completed_at TIMESTAMPTZ NOT NULL,
  archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (status IN ('COMPLETED', 'FAILED')),
  PRIMARY KEY (completed_at, command_id)
) PARTITION BY RANGE (completed_at);

CREATE TABLE IF NOT EXISTS command_log.command_results_archive_default
  PARTITION OF command_log.command_results_archive DEFAULT;

CREATE INDEX IF NOT EXISTS idx_command_log_results_archive_command
  ON command_log.command_results_archive(command_id, completed_at);

CREATE INDEX IF NOT EXISTS idx_command_log_results_archive_status_completed
  ON command_log.command_results_archive(status, completed_at, command_id);
