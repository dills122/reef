-- Keep command_work_queue focused on active work; terminal state lives in command_results.

ALTER TABLE command_log.command_results
  ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'COMPLETED';

ALTER TABLE command_log.command_results
  DROP CONSTRAINT IF EXISTS command_results_status_check;

ALTER TABLE command_log.command_results
  ADD CONSTRAINT command_results_status_check
  CHECK (status IN ('COMPLETED', 'FAILED'));

UPDATE command_log.command_results results
SET status = commands.status
FROM command_log.commands commands
WHERE commands.command_id = results.command_id
  AND commands.status IN ('COMPLETED', 'FAILED');

DELETE FROM command_log.command_work_queue
WHERE status IN ('COMPLETED', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_command_log_results_status_completed
  ON command_log.command_results(status, completed_at, command_id);
