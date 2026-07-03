-- Preserve terminal status metadata after active queue rows are removed.

ALTER TABLE command_log.command_results
  ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS last_error TEXT NOT NULL DEFAULT '';

UPDATE command_log.command_results results
SET attempt_count = commands.attempt_count,
    last_error = commands.last_error
FROM command_log.commands commands
WHERE commands.command_id = results.command_id;
