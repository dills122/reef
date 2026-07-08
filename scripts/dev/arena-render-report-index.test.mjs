import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-render-report-index-"));
const positivePath = join(dir, "positive.json");
const negativePath = join(dir, "negative.json");
const htmlPath = join(dir, "index.html");

writeFileSync(positivePath, JSON.stringify(report("positive-run", "completed", [])));
writeFileSync(negativePath, JSON.stringify(report("negative-run", "completed_with_freezes", ["custom-too-many-orders"])));

const result = spawnSync(
  "node",
  [
    "scripts/dev/arena-render-report-index.mjs",
    `--reports=${positivePath},${negativePath}`,
    `--out=${htmlPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(result.status, 0, result.stderr);
const html = readFileSync(htmlPath, "utf8");
assert.match(html, /Reef Arena Report Index/);
assert.match(html, /positive-run/);
assert.match(html, /negative-run/);
assert.match(html, /completed_with_freezes/);
assert.match(html, /custom-too-many-orders/);
assert.match(html, /docker-compose-loopback/);

console.log("arena report index render checks passed");

function report(runId, status, frozenBots) {
  return {
    schemaVersion: "reef.arena.localTickRun.v0",
    generatedAt: "2026-07-08T00:00:00.000Z",
    runId,
    status,
    mode: {
      modeId: "equity-sprint",
      scoringPolicyVersion: "score-v0",
    },
    totals: {
      ticks: 15,
      venueCommands: 14,
      submittedCommands: 14,
    },
    botResults: [
      {
        botId: "custom-technical-indicator",
        versionId: "local-1",
        score: 1000000,
        scoreEligible: true,
        publicLeaderboard: true,
        disqualified: false,
      },
    ],
    leaderboard: [
      {
        rank: 1,
        botId: "custom-technical-indicator",
        versionId: "local-1",
        score: 1000000,
      },
    ],
    enforcementEvents: frozenBots.map((botId) => ({
      botId,
      versionId: "local-1",
      decision: "freeze",
      reasonCode: "tick_policy_violation",
      reason: "maxActionsPerTick exceeded",
    })),
    persistence: {
      enabled: true,
      skipped: false,
      mode: "docker-compose-loopback",
      operations: [{ ok: true }],
    },
  };
}
