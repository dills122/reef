import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-persist-report-local-"));
const reportPath = join(dir, "report.json");
const outPath = join(dir, "persisted.json");

writeFileSync(
  reportPath,
  JSON.stringify({
    schemaVersion: "reef.arena.localTickRun.v0",
    runId: "arena-persist-test",
    status: "completed",
    mode: {
      modeId: "equity-sprint",
      scenarioId: "arena-equity-sprint-v1",
      seed: 170707,
      scoringPolicyVersion: "score-v0",
      riskPolicyVersion: "arena-risk-v0",
    },
    botResults: [
      {
        botId: "custom-technical-indicator",
        versionId: "local-1",
        runnerKey: "technical",
        role: "competitor",
        scoreEligible: true,
        publicLeaderboard: true,
        disqualified: false,
        score: 1000100,
        actionsProposed: 1,
        venueCommands: 1,
        dataCalls: 3,
      },
    ],
    leaderboard: [
      {
        rank: 1,
        botId: "custom-technical-indicator",
        versionId: "local-1",
        score: 1000100,
        disqualified: false,
      },
    ],
    enforcementEvents: [],
    sessionReports: [
      {
        bot: {
          botId: "custom-technical-indicator",
          versionId: "local-1",
          role: "competitor",
          entryPath: "packages/bot-sdk/examples/technical-indicator-strategy-bot.ts",
          artifact: {
            manifest: {
              sourceHash: "sha256:source",
              artifactHash: "sha256:artifact",
            },
          },
        },
      },
    ],
  }),
);

const result = spawnSync(
  "node",
  [
    "scripts/dev/arena-persist-report-local.mjs",
    `--report=${reportPath}`,
    `--out=${outPath}`,
    "--dry-run",
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(result.status, 0, result.stderr);
const persisted = JSON.parse(readFileSync(outPath, "utf8"));
assert.equal(persisted.persistence.enabled, true);
assert.equal(persisted.persistence.mode, "dry-run");
assert.ok(persisted.persistence.operations.length >= 7);
assert.equal(persisted.persistence.leaderboardEntry.botId, "custom-technical-indicator");

console.log("arena local report persistence dry-run checks passed");
