import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  analyzeArenaActorDiagnostics,
  actorDiagnosticsCliSummary,
  parseArgs,
} from "./analyze-arena-actor-diagnostics.mjs";

const dir = await mkdtemp(path.join(tmpdir(), "reef-actor-diagnostics-test-"));
const catalogPath = path.join(dir, "actor-profiles.json");
const reportPath = path.join(dir, "report.json");
const manifestPath = path.join(dir, "manifest.json");

await writeFile(catalogPath, JSON.stringify({
  catalogId: "test-catalog",
  version: "test-v1",
  profiles: [
    {
      profileId: "mm-tight",
      version: "v1",
      actorClass: "house_market_maker",
      difficultyBucket: "neutral-liquidity",
      scoreEffect: "diagnostic-only",
      params: {
        aggression: 0.35,
        inventorySkew: 0.2,
        orderRate: "responsive",
        quoteSize: 10,
        quoteSpreadBps: 20,
        riskDiscipline: "high",
      },
    },
    {
      profileId: "npc-toxic",
      version: "v1",
      actorClass: "npc_flow",
      difficultyBucket: "toxic-momentum",
      scoreEffect: "difficulty-bucket",
      params: {
        aggression: 0.95,
        cancelDiscipline: "low",
        maxSpreadCrossBps: 250,
        orderRate: "high",
        riskDiscipline: "low",
      },
    },
  ],
}));

await writeFile(reportPath, JSON.stringify({
  runId: "run-1",
  mode: { modeId: "equity-multi-local" },
  botResults: [
    {
      botId: "mm-a",
      actorClass: "house_market_maker",
      actorProfile: {
        profileId: "mm-tight",
        profileVersion: "v1",
        actorClass: "house_market_maker",
        difficultyBucket: "neutral-liquidity",
        scoreEffect: "diagnostic-only",
        params: {
          aggression: 0.35,
          inventorySkew: 0.2,
          orderRate: "responsive",
          quoteSize: 10,
          quoteSpreadBps: 20,
          riskDiscipline: "high",
        },
      },
      tradingMetrics: {
        commands: { submitted: 4, completed: 4, failed: 0, rejected: 0, timedOut: 0 },
        orderFlow: { submittedLimitOrders: 4, cancelCommands: 1, grossSubmittedQuantity: 40, grossSubmittedNotional: 4000 },
        executions: { fillCount: 1, filledQuantity: 10, grossNotional: 1000 },
        pnl: { total: 5 },
        inventory: { grossNotional: 100 },
      },
      conductMetrics: { cancelReplaceRatio: 0.25, invalidIntentRate: 0, timeoutRate: 0, maxVenueCommandsPerTick: 2 },
      scoreBreakdown: {
        scoreEligible: false,
        components: { marketInteraction: 0, risk: 0, conduct: 0 },
        diagnostics: { fillRatio: 0.25, completionRate: 1, inventoryExposureRatio: 0.025 },
      },
      liquidityDiagnostics: {
        scoreNeutral: true,
        flags: [],
        quoteQuality: { medianQuotedSpreadBps: 20, p95QuotedSpreadBps: 20 },
      },
    },
    {
      botId: "npc-a",
      actorClass: "npc_flow",
      actorProfile: {
        profileId: "npc-toxic",
        profileVersion: "v1",
        actorClass: "npc_flow",
        difficultyBucket: "toxic-momentum",
        scoreEffect: "difficulty-bucket",
        params: {
          aggression: 0.95,
          cancelDiscipline: "low",
          maxSpreadCrossBps: 250,
          orderRate: "high",
          riskDiscipline: "low",
        },
      },
      tradingMetrics: {
        commands: { submitted: 6, completed: 6, failed: 0, rejected: 0, timedOut: 0 },
        orderFlow: { submittedLimitOrders: 6, cancelCommands: 0, grossSubmittedQuantity: 30, grossSubmittedNotional: 3000 },
        executions: { fillCount: 2, filledQuantity: 10, grossNotional: 1000 },
        pnl: { total: -2 },
        inventory: { grossNotional: 0 },
      },
      conductMetrics: { cancelReplaceRatio: 0, invalidIntentRate: 0, timeoutRate: 0, maxVenueCommandsPerTick: 3 },
      scoreBreakdown: {
        scoreEligible: false,
        components: { marketInteraction: 0, risk: 0, conduct: 0 },
        diagnostics: { fillRatio: 0.333333, completionRate: 1, inventoryExposureRatio: 0 },
      },
    },
  ],
}));
await writeFile(manifestPath, JSON.stringify({
  entries: [{ status: "completed", reportPath: path.basename(reportPath) }],
}));

const options = parseArgs([
  `--manifest=${manifestPath}`,
  `--actor-profile-catalog=${catalogPath}`,
  "--out=/tmp/out.json",
]);
assert.equal(options.manifest, manifestPath);
assert.equal(options.actorProfileCatalog, catalogPath);
assert.equal(options.out, "/tmp/out.json");

const diagnostics = analyzeArenaActorDiagnostics({
  manifest: manifestPath,
  actorProfileCatalog: catalogPath,
});
assert.equal(diagnostics.schemaVersion, "reef.arena.actorDiagnostics.v1");
assert.equal(diagnostics.coverage.observationCount, 2);
assert.equal(diagnostics.coverage.actorClassCounts.house_market_maker, 1);
assert.equal(diagnostics.profileDiagnostics.length, 2);
assert.equal(diagnostics.profileDiagnostics.find((entry) => entry.profileId === "mm-tight").metrics.fillCount.avg, 1);
assert.equal(diagnostics.profileDiagnostics.find((entry) => entry.profileId === "npc-toxic").metrics.submittedCommands.avg, 6);
assert.equal(diagnostics.knobDiagnostics.find((entry) => entry.knob === "aggression").observedValueCount, 2);
assert.equal(diagnostics.knobDiagnostics.find((entry) => entry.knob === "quoteSpreadBps").values[0].metrics.medianQuotedSpreadBps.avg, 20);
assert.equal(diagnostics.caveats.includes("low-run-count"), true);

const summary = actorDiagnosticsCliSummary(diagnostics, "/tmp/actor-diagnostics.json");
assert.equal(summary.schemaVersion, "reef.arena.actorDiagnosticsCliSummary.v1");
assert.equal(summary.outPath, "/tmp/actor-diagnostics.json");
assert.equal(summary.profiles.find((entry) => entry.profileId === "npc-toxic").fillCountAvg, 2);
assert.equal(summary.knobs.find((entry) => entry.knob === "aggression").observedValueCount, 2);

console.log("arena actor diagnostics checks passed");
