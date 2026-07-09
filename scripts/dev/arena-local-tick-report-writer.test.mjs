import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-local-tick-report-writer-"));
const reportPath = join(dir, "arena-local-tick-run.json");
const compactReportPath = join(dir, "arena-local-tick-run-compact.json");

const result = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--duration-seconds=1",
    "--tick-interval-ms=500",
    `--out=${reportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
const report = JSON.parse(readFileSync(reportPath, "utf8"));
assert.equal(report.schemaVersion, "reef.arena.localTickRun.v0");
assert.equal(report.status, "completed");
assert.equal(report.runPlan.durationSeconds, 1);
assert.equal(report.runPlan.schedulingMode, "shared-arena-time");
assert.equal(report.totals.ticks, 16);
assert.equal(report.sessionReports.length, 5);
assert.equal(report.sessionReports.flatMap((session) => session.ticks).length, report.totals.ticks);
assert.equal(report.healthSamples.length, 2);

const compactResult = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--duration-seconds=1",
    "--tick-interval-ms=500",
    "--report-shape=compact",
    `--out=${compactReportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(compactResult.status, 0, `${compactResult.stdout}\n${compactResult.stderr}`);
const compactReport = JSON.parse(readFileSync(compactReportPath, "utf8"));
assert.equal(compactReport.schemaVersion, "reef.arena.localTickRun.v0");
assert.equal(compactReport.reportShape, "compact");
assert.equal(compactReport.status, "completed");
assert.equal(compactReport.totals.ticks, 16);
assert.equal(compactReport.sessionReports, undefined);
assert.equal(compactReport.healthSamples, undefined);
assert.equal(compactReport.omitted.sessionReports, 5);
assert.equal(compactReport.omitted.healthSamples, 2);
assert.equal(compactReport.latencySummary.tickElapsedMs.count, 16);

console.log("arena local tick report writer checks passed");
