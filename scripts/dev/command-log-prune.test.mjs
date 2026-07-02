import test from "node:test";
import assert from "node:assert/strict";
import {
  buildDeleteBatchSql,
  buildEligibleCountSql,
  buildQueueCountsSql,
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
  assert.match(sql, /86400::double precision/);
});

test("builds batched delete through commands for cascading terminal history", () => {
  const sql = buildDeleteBatchSql({ olderThanSeconds: 0, batchSize: 50000 });

  assert.match(sql, /WITH eligible AS/);
  assert.match(sql, /LIMIT 50000/);
  assert.match(sql, /DELETE FROM command_log\.commands commands/);
  assert.match(sql, /RETURNING commands\.command_id/);
});

test("builds active queue count SQL", () => {
  const sql = buildQueueCountsSql();

  assert.match(sql, /FROM command_log\.command_work_queue/);
  assert.match(sql, /GROUP BY status/);
});
