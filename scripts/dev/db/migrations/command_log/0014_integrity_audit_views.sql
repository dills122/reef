-- Read-only integrity diagnostics for hot command-log tables.
--
-- command_payloads, command_work_queue, and command_results intentionally avoid
-- same-schema foreign keys on the hot path. This view keeps the replacement
-- audit checks explicit without adding insert/delete-time constraint work.

CREATE OR REPLACE VIEW command_log.command_integrity_violations AS
SELECT
  'orphan_payload'::TEXT AS violation_type,
  payloads.command_id,
  jsonb_build_object('table', 'command_payloads') AS detail
FROM command_log.command_payloads payloads
LEFT JOIN command_log.commands commands ON commands.command_id = payloads.command_id
WHERE commands.command_id IS NULL

UNION ALL

SELECT
  'orphan_work_queue'::TEXT AS violation_type,
  queue.command_id,
  jsonb_build_object('table', 'command_work_queue', 'status', queue.status) AS detail
FROM command_log.command_work_queue queue
LEFT JOIN command_log.commands commands ON commands.command_id = queue.command_id
WHERE commands.command_id IS NULL

UNION ALL

SELECT
  'orphan_result'::TEXT AS violation_type,
  results.command_id,
  jsonb_build_object('table', 'command_results', 'status', results.status) AS detail
FROM command_log.command_results results
LEFT JOIN command_log.commands commands ON commands.command_id = results.command_id
WHERE commands.command_id IS NULL

UNION ALL

SELECT
  'active_command_missing_queue'::TEXT AS violation_type,
  commands.command_id,
  jsonb_build_object('command_status', commands.status) AS detail
FROM command_log.commands commands
LEFT JOIN command_log.command_work_queue queue ON queue.command_id = commands.command_id
LEFT JOIN command_log.command_results results ON results.command_id = commands.command_id
WHERE commands.status IN ('RECEIVED', 'PROCESSING')
  AND queue.command_id IS NULL
  AND results.command_id IS NULL

UNION ALL

SELECT
  'terminal_result_still_queued'::TEXT AS violation_type,
  queue.command_id,
  jsonb_build_object('queue_status', queue.status, 'result_status', results.status) AS detail
FROM command_log.command_work_queue queue
JOIN command_log.command_results results ON results.command_id = queue.command_id
WHERE results.status IN ('COMPLETED', 'FAILED');

CREATE OR REPLACE FUNCTION command_log.command_integrity_summary()
RETURNS TABLE (
  violation_type TEXT,
  violation_count BIGINT
)
LANGUAGE sql
STABLE
AS $$
  SELECT violations.violation_type, COUNT(*)::BIGINT AS violation_count
  FROM command_log.command_integrity_violations violations
  GROUP BY violations.violation_type
  ORDER BY violations.violation_type;
$$;
