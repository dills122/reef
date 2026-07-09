import test from "node:test";
import assert from "node:assert/strict";
import {
  buildArchiveBatchSql,
  buildArchiveEligibleCountSql,
  buildArchiveVacuumSql,
} from "./command-log-archive.mjs";

test("builds archive eligible count SQL", () => {
  const sql = buildArchiveEligibleCountSql({ olderThanSeconds: 86400 });

  assert.match(sql, /FROM command_log\.command_results results/);
  assert.match(sql, /JOIN command_log\.commands commands/);
  assert.match(sql, /command_log\.command_work_queue queue/);
  assert.match(sql, /command_log\.retention_pins pins/);
  assert.match(sql, /86400::double precision/);
});

test("builds bounded archive batch SQL", () => {
  const sql = buildArchiveBatchSql({ olderThanSeconds: 0, batchSize: 50000 });

  assert.match(sql, /WITH candidates AS/);
  assert.match(sql, /LIMIT 50000/);
  assert.match(sql, /FOR UPDATE OF results SKIP LOCKED/);
  assert.match(sql, /INSERT INTO command_log\.command_results_archive/);
  assert.match(sql, /ON CONFLICT \(completed_at, command_id\) DO UPDATE/);
  assert.match(sql, /DELETE FROM command_log\.command_results results/);
  assert.match(sql, /deleted_live_count/);
  assert.doesNotMatch(sql, /DELETE FROM command_log\.commands/);
  assert.doesNotMatch(sql, /DELETE FROM command_log\.command_payloads/);
});

test("builds archive vacuum commands", () => {
  const commands = buildArchiveVacuumSql();

  assert.equal(commands.length, 2);
  assert.equal(commands[0].table, "command_log.command_results");
  assert.equal(commands[1].table, "command_log.command_results_archive");
  assert.match(commands[0].sql, /PARALLEL 0/);
});
