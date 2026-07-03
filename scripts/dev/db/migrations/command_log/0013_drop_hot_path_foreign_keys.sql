-- The command-log child tables are written from command-scoped application paths.
-- Dropping same-schema foreign keys removes hot insert/delete checks while
-- preserving lifecycle cleanup through explicit prune deletes and bootstrap
-- active-queue reconstruction.

ALTER TABLE command_log.command_payloads
  DROP CONSTRAINT IF EXISTS command_payloads_command_id_fkey;

ALTER TABLE command_log.command_work_queue
  DROP CONSTRAINT IF EXISTS command_work_queue_command_id_fkey;

ALTER TABLE command_log.command_results
  DROP CONSTRAINT IF EXISTS command_results_command_id_fkey;
