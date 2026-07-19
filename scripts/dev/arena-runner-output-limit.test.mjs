import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const tempDir = mkdtempSync(join(tmpdir(), "reef-arena-runner-output-limit-"));
const modePath = join(tempDir, "mode.json");
const reportPath = join(tempDir, "report.json");
const baseMode = JSON.parse(readFileSync(join(repoRoot, "packages/scenario-definitions/arena/equity-sprint.v1.json"), "utf8"));

writeFileSync(modePath, `${JSON.stringify({
  ...baseMode,
  modeId: "runner-output-limit",
  botRefs: ["builtin-npc-momentum"],
  durationSeconds: 5,
  tickIntervalMs: 25,
  healthSampleIntervalMs: 1000,
  healthTargets: {
    minTicksWithVenueCommandsPct: 0,
    maxFailedTicks: 0,
    maxFreezeCount: 0,
  },
}, null, 2)}\n`);

const run = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    `--mode=${modePath}`,
    "--compartment=vm",
    "--runner-worker-scope=shared",
    "--runner-max-output-bytes=32768",
    "--report-shape=compact",
    `--out=${reportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8", timeout: 60_000 },
);

assert.equal(run.status, 0, `arena runner output-limit regression failed\nstdout:\n${run.stdout}\nstderr:\n${run.stderr}`);
const report = JSON.parse(readFileSync(reportPath, "utf8"));
assert.equal(report.status, "completed");
assert.equal(report.totals.ticks, 200);
assert.equal(report.totals.failedTicks, 0);
assert.equal(report.enforcementEvents.filter((event) => event.decision === "freeze").length, 0);

console.log("arena runner output-limit checks passed");
