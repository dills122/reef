import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-actor-profile-behavior-"));
const reportPath = join(dir, "arena-multi-local-compact.json");

const result = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--mode=packages/scenario-definitions/arena/equity-multi-local.v1.json",
    "--duration-seconds=4",
    "--tick-interval-ms=500",
    "--report-shape=compact",
    `--out=${reportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
const report = JSON.parse(readFileSync(reportPath, "utf8"));
assert.deepEqual(report.mode.npcDifficultyBuckets, ["benign-noise", "toxic-momentum"]);
assert.equal(report.runPlan.actorProfiles.byDifficultyBucket["toxic-momentum"], 5);

const aggressiveTakers = report.botResults.filter((entry) => entry.actorProfile?.profileId === "npc-bad-aggressive-retail");
assert.equal(aggressiveTakers.length, 5);
for (const taker of aggressiveTakers) {
  assert.equal(taker.actorClass, "npc_flow");
  assert.equal(taker.actorProfile.scoreEffect, "difficulty-bucket");
  assert.equal(taker.scoreBreakdown.scoreEligible, false);
  assert.equal(taker.scoreBreakdown.scoringMode, "difficulty-context-only");
  assert.equal(taker.scoreBreakdown.shadowScore, null);
  assert.equal(taker.actorProfile.params.maxSpreadCrossBps, 250);
  assert.equal(taker.venueCommands, 3);
  assert.equal(taker.tradingMetrics.orderFlow.submittedLimitOrders, 3);
  assert.equal(taker.tradingMetrics.orderFlow.bySide.BUY, 1);
  assert.equal(taker.tradingMetrics.orderFlow.bySide.SELL, 2);
}

const competitors = report.botResults.filter((entry) => entry.actorClass === "competitor");
assert.equal(competitors.length, 11);
for (const competitor of competitors) {
  assert.equal(competitor.scoreBreakdown.scoreEligible, true);
  assert.equal(competitor.scoreBreakdown.publicScore, competitor.score);
  assert.equal(competitor.scoreBreakdown.diagnostics.difficultyMultiplier, 1.1);
  assert.deepEqual(competitor.scoreBreakdown.diagnostics.npcDifficultyBuckets, ["benign-noise", "toxic-momentum"]);
}

const liquidityProviders = report.botResults.filter((entry) => entry.actorClass === "house_market_maker");
assert.equal(liquidityProviders.length, 6);
for (const provider of liquidityProviders) {
  assert.equal(provider.scoreBreakdown.scoreEligible, false);
  assert.equal(provider.scoreBreakdown.scoreEffect, "diagnostic-only");
  assert.equal(provider.scoreBreakdown.publicScore, null);
  assert.equal(provider.scoreBreakdown.shadowScore, null);
}

console.log("arena actor profile behavior checks passed");
