import test from "node:test";
import assert from "node:assert/strict";
import {
  buildCreateArchivePartitionSql,
  buildDropArchivePartitionSql,
  buildExportArchivePartitionSql,
  buildListArchivePartitionsSql,
  parseArchivePartitionMonth,
} from "./command-log-archive-partitions.mjs";

test("parses archive partition month bounds", () => {
  assert.deepEqual(parseArchivePartitionMonth("2026-07"), {
    value: "2026-07",
    suffix: "2026_07",
    start: "2026-07-01T00:00:00Z",
    end: "2026-08-01T00:00:00Z",
  });
  assert.equal(parseArchivePartitionMonth("2026-12").end, "2027-01-01T00:00:00Z");
  assert.throws(() => parseArchivePartitionMonth("2026-13"), /invalid archive partition month/);
  assert.throws(() => parseArchivePartitionMonth("202607"), /invalid archive partition month/);
});

test("builds archive partition list SQL", () => {
  const sql = buildListArchivePartitionsSql();

  assert.match(sql, /FROM pg_inherits/);
  assert.match(sql, /command_results_archive/);
  assert.match(sql, /pg_get_expr/);
});

test("builds monthly archive partition create SQL", () => {
  const sql = buildCreateArchivePartitionSql({ month: "2026-07" });

  assert.match(sql, /CREATE TABLE IF NOT EXISTS command_log\.command_results_archive_2026_07/);
  assert.match(sql, /PARTITION OF command_log\.command_results_archive/);
  assert.match(sql, /FROM \('2026-07-01T00:00:00Z'::timestamptz\)/);
  assert.match(sql, /TO \('2026-08-01T00:00:00Z'::timestamptz\)/);
});

test("builds monthly archive partition drop SQL", () => {
  const sql = buildDropArchivePartitionSql({ month: "2026-07" });

  assert.match(sql, /DETACH PARTITION command_log\.command_results_archive_2026_07/);
  assert.match(sql, /DROP TABLE command_log\.command_results_archive_2026_07/);
});

test("builds monthly archive partition export SQL", () => {
  const sql = buildExportArchivePartitionSql({ month: "2026-07" });

  assert.match(sql, /COPY \(/);
  assert.match(sql, /FROM command_log\.command_results_archive_2026_07 archive_row/);
  assert.match(sql, /ORDER BY completed_at, command_id/);
  assert.match(sql, /TO STDOUT/);
});
