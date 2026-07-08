import test from "node:test";
import assert from "node:assert/strict";
import {
  buildDeleteBatchSql,
  buildDeleteOrphanRowsSql,
  buildEligibleCountSql,
  buildOrphanCountSql,
  buildQueueCountsSql,
  buildRetentionPinExclusionPredicate,
  buildVacuumSql,
  parseDurationSeconds,
} from "./command-log-prune.mjs";

test("parses prune duration values", () => {
  assert.equal(parseDurationSeconds("0s"), 0);
  assert.equal(parseDurationSeconds("30m"), 1800);
  assert.equal(parseDurationSeconds("24h"), 86400);
  assert.equal(parseDurationSeconds("7d"), 604800);
  assert.equal(parseDurationSeconds("500ms"), 0.5);
  assert.throws(() => parseDurationSeconds("1 week"), /invalid duration/);
});

test("builds terminal-only eligible count SQL", () => {
  const sql = buildEligibleCountSql({ olderThanSeconds: 86400 });

  assert.match(sql, /FROM command_log\.command_results results/);
  assert.match(sql, /NOT EXISTS/);
  assert.match(sql, /command_log\.command_work_queue queue/);
  assert.match(sql, /command_log\.retention_pins pins/);
  assert.match(sql, /86400::double precision/);
});

test("builds batched delete for terminal command history", () => {
  const sql = buildDeleteBatchSql({ olderThanSeconds: 0, batchSize: 50000 });

  assert.match(sql, /WITH eligible AS/);
  assert.match(sql, /LIMIT 50000/);
  assert.match(sql, /DELETE FROM command_log\.command_payloads payloads/);
  assert.match(sql, /DELETE FROM command_log\.command_results results/);
  assert.match(sql, /DELETE FROM command_log\.commands commands/);
  assert.match(sql, /RETURNING commands\.command_id/);
  assert.match(sql, /idempotency_prefix/);
});

test("builds command-log orphan diagnostics and cleanup SQL", () => {
  const countSql = buildOrphanCountSql();
  const deleteSql = buildDeleteOrphanRowsSql();

  assert.match(countSql, /FROM command_log\.command_integrity_violations/);
  assert.match(countSql, /orphan_payload/);
  assert.match(deleteSql, /DELETE FROM command_log\.command_payloads payloads/);
  assert.match(deleteSql, /DELETE FROM command_log\.command_work_queue queue/);
  assert.match(deleteSql, /DELETE FROM command_log\.command_results results/);
  assert.match(deleteSql, /NOT EXISTS/);
  assert.match(deleteSql, /SELECT COUNT\(\*\) FROM deleted_payloads/);
});

test("builds active queue count SQL", () => {
  const sql = buildQueueCountsSql();

  assert.match(sql, /FROM command_log\.command_work_queue/);
  assert.match(sql, /GROUP BY status/);
});

test("builds low-shared-memory vacuum commands", () => {
  const commands = buildVacuumSql();

  assert.equal(commands.length, 4);
  assert.match(commands[0].sql, /PARALLEL 0/);
  assert.equal(commands[0].table, "command_log.commands");
  assert.equal(commands[1].table, "command_log.command_payloads");
});

test("builds retention pin exclusion predicate", () => {
  const sql = buildRetentionPinExclusionPredicate("commands");

  assert.match(sql, /selector_type = 'command_id'/);
  assert.match(sql, /commands\.idempotency_key LIKE pins\.selector_value/);
  assert.match(sql, /selector_type = 'client_id'/);
  assert.match(sql, /selector_type = 'run_id'/);
  assert.match(sql, /commands\.run_id/);
});
