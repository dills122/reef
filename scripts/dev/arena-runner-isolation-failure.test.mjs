import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const tempDir = mkdtempSync(join(tmpdir(), "reef-arena-isolation-failure-"));
const catalogPath = join(tempDir, "bot-catalog.json");
const modePath = join(tempDir, "mode.json");
const reportPath = join(tempDir, "report.json");

const baseCatalog = JSON.parse(readFileSync(join(repoRoot, "packages/scenario-definitions/arena/bot-catalog.v1.json"), "utf8"));
const baseMode = JSON.parse(readFileSync(join(repoRoot, "packages/scenario-definitions/arena/equity-sprint.v1.json"), "utf8"));

writeFileSync(catalogPath, `${JSON.stringify({
  ...baseCatalog,
  bots: [{
    botId: "custom-sync-hanging",
    displayName: "Sync Hanging Bot",
    versionId: "local-1",
    runnerKey: "sync-hanging",
    role: "competitor",
    entryPath: "packages/bot-sdk/test-fixtures/bad-bots/sync-hanging-bot.ts",
    riskProfile: "competitor_standard",
    scoreEligible: true,
    publicLeaderboard: false,
  }],
}, null, 2)}\n`);

writeFileSync(modePath, `${JSON.stringify({
  ...baseMode,
  modeId: "runner-isolation-failure",
  catalogPath,
  botRefs: ["custom-sync-hanging"],
  ticks: 1,
  healthTargets: {
    minTicksWithVenueCommandsPct: 0,
    maxFailedTicks: 1,
    maxFreezeCount: 1,
  },
}, null, 2)}\n`);

const run = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    `--mode=${modePath}`,
    "--compartment=ses",
    "--runner-isolation=process",
    "--runner-worker-scope=per-bot",
    "--runner-request-timeout-ms=250",
    "--duration-seconds=1",
    "--tick-interval-ms=1000",
    "--report-shape=compact",
    "--expect-freeze-bots=custom-sync-hanging",
    `--out=${reportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(run.status, 0, `arena runner isolation failure smoke failed\nstdout:\n${run.stdout}\nstderr:\n${run.stderr}`);

const report = JSON.parse(readFileSync(reportPath, "utf8"));
const event = report.enforcementEvents.find((candidate) => candidate.botId === "custom-sync-hanging");
assert.equal(event?.decision, "freeze");
assert.equal(event.reasonCode, "runner_isolation_failure");
assert.equal(event.runnerFailure.code, "runner_worker_tick_failed");
assert.equal(report.botResults[0].disqualified, true);

console.log("arena runner isolation failure checks passed");
