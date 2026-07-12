import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  actorCalibrationCliSummary,
  applyCalibrationEnvironment,
  buildActorInfluenceSummary,
  buildActorCalibrationEntries,
  buildArenaRunArgs,
  catalogWithActorOverride,
  parseArgs,
} from "./run-arena-actor-calibration-matrix.mjs";

const dir = await mkdtemp(path.join(tmpdir(), "reef-actor-calibration-test-"));
const generatedDir = path.join(dir, "generated");
const mode = {
  schemaVersion: "reef.arena.mode.v1",
  modeId: "equity-test",
  version: "v1",
  seed: 42,
  scenarioId: "arena-equity-test-v1",
  venueSessionId: "arena-equity-test",
  catalogPath: "packages/scenario-definitions/arena/bot-catalog.v1.json",
  actorProfileCatalogPath: "packages/scenario-definitions/arena/actor-profiles.v1.json",
  actorProfileDefaults: {
    "market-maker": "mm-tight-bluechip",
    npc: "npc-noise-small-random",
    competitor: "competitor-standard",
  },
  botRefs: ["mm-a", "npc-a", "competitor-a"],
};
const botCatalog = {
  schemaVersion: "reef.arena.botCatalog.v1",
  catalogId: "test-catalog",
  version: "test",
  bots: [
    { botId: "mm-a", role: "market-maker", riskProfile: "house_liquidity" },
    { botId: "npc-a", role: "npc", actorProfileRef: "npc-bad-aggressive-retail", riskProfile: "npc_standard" },
    { botId: "competitor-a", role: "competitor", riskProfile: "competitor_standard" },
  ],
};

const overridden = catalogWithActorOverride(botCatalog, mode, "npc-bad-aggressive-retail", "aggression", 0.35);
assert.deepEqual(overridden.bots.find((entry) => entry.botId === "npc-a").actorProfileParams, { aggression: 0.35 });
assert.equal(overridden.bots.find((entry) => entry.botId === "mm-a").actorProfileParams, undefined);

const thinWideEnvironment = {
  id: "thin-wide-liquidity",
  description: "Thin, wide liquidity for test.",
  modePatch: {
    houseLiquidityDefaults: {
      targetSpreadBps: 100,
      quoteSize: 2,
    },
    healthTargets: {
      maxMedianQuotedSpreadBps: 125,
    },
    botRefs: ["mm-a", "npc-a"],
  },
  actorProfileParamOverrides: {
    "mm-tight-bluechip": {
      quoteSpreadBps: 100,
      quoteSize: 2,
    },
  },
};
const configured = applyCalibrationEnvironment(mode, botCatalog, thinWideEnvironment);
assert.equal(configured.mode.houseLiquidityDefaults.targetSpreadBps, 100);
assert.equal(configured.mode.houseLiquidityDefaults.quoteSize, 2);
assert.equal(configured.mode.healthTargets.maxMedianQuotedSpreadBps, 125);
assert.deepEqual(configured.mode.botRefs, ["mm-a", "npc-a"]);
assert.equal(mode.houseLiquidityDefaults, undefined);
assert.deepEqual(configured.botCatalog.bots.find((entry) => entry.botId === "mm-a").actorProfileParams, {
  quoteSpreadBps: 100,
  quoteSize: 2,
});
const quoteSpreadOverride = catalogWithActorOverride(configured.botCatalog, configured.mode, "mm-tight-bluechip", "quoteSpreadBps", 10);
assert.equal(quoteSpreadOverride.catalogId, "test-catalog-actor-calibration");
assert.deepEqual(quoteSpreadOverride.bots.find((entry) => entry.botId === "mm-a").actorProfileParams, {
  quoteSpreadBps: 10,
  quoteSize: 2,
});

const groups = [
  {
    id: "npc-aggression",
    description: "NPC aggression",
    targetProfileId: "npc-bad-aggressive-retail",
    knob: "aggression",
    values: [0.35, 0.95],
    metricsToWatch: ["fillCount"],
  },
];
const entries = buildActorCalibrationEntries({ mode, botCatalog, groups, generatedDir });
assert.equal(entries.length, 3);
assert.equal(entries[0].id, "baseline");
assert.equal(entries[1].id, "npc-aggression-0p35");
assert.equal(entries[2].value, 0.95);

const generatedMode = JSON.parse(await readFile(entries[1].modePath, "utf8"));
const generatedCatalog = JSON.parse(await readFile(entries[1].botCatalogPath, "utf8"));
assert.equal(generatedMode.modeId, "equity-test-actor-npc-aggression-0p35");
assert.equal(generatedMode.scenarioId, "arena-equity-test-v1-actor-npc-aggression-0p35");
assert.equal(generatedMode.catalogPath, entries[1].botCatalogPath);
assert.deepEqual(generatedCatalog.bots.find((entry) => entry.botId === "npc-a").actorProfileParams, { aggression: 0.35 });

const environmentEntries = buildActorCalibrationEntries({
  mode: configured.mode,
  botCatalog: configured.botCatalog,
  groups,
  includeBaseline: true,
  generatedDir: path.join(dir, "environment-generated"),
});
const environmentBaselineCatalog = JSON.parse(await readFile(environmentEntries[0].botCatalogPath, "utf8"));
const environmentGroupCatalog = JSON.parse(await readFile(environmentEntries[1].botCatalogPath, "utf8"));
assert.deepEqual(environmentBaselineCatalog.bots.find((entry) => entry.botId === "mm-a").actorProfileParams, {
  quoteSpreadBps: 100,
  quoteSize: 2,
});
assert.deepEqual(environmentGroupCatalog.bots.find((entry) => entry.botId === "npc-a").actorProfileParams, {
  aggression: 0.35,
});
assert.deepEqual(environmentGroupCatalog.bots.find((entry) => entry.botId === "mm-a").actorProfileParams, {
  quoteSpreadBps: 100,
  quoteSize: 2,
});

const args = buildArenaRunArgs(entries[1], "/tmp/report.json", {
  submitMode: "live",
  compartment: "vm",
  durationSeconds: 12,
  tickIntervalMs: 500,
  venueUrl: "http://127.0.0.1:8080",
  seedReference: true,
});
assert.deepEqual(args, [
  "scripts/dev/arena-local-tick-run.mjs",
  "--compartment=vm",
  "--submit-mode=live",
  `--mode=${entries[1].modePath}`,
  "--report-shape=compact",
  "--out=/tmp/report.json",
  "--duration-seconds=12",
  "--tick-interval-ms=500",
  "--venue-url=http://127.0.0.1:8080",
  "--seed-reference",
]);

const options = parseArgs([
  "--out-dir=/tmp/calibration",
  "--group=npc-aggression",
  "--group=mm-quote-size",
  "--environment=thin-wide-liquidity",
  "--no-baseline",
]);
assert.equal(options.outDir, "/tmp/calibration");
assert.deepEqual(options.groups, ["npc-aggression", "mm-quote-size"]);
assert.equal(options.environment, "thin-wide-liquidity");
assert.equal(options.includeBaseline, false);

const summary = actorCalibrationCliSummary({
  manifestPath: "/tmp/calibration/manifest.json",
  diagnosticsPath: "/tmp/calibration/actor-diagnostics.json",
  influenceSummaryPath: "/tmp/calibration/actor-influence-summary.json",
  submitMode: "dry-run",
  environment: {
    id: "thin-wide-liquidity",
    description: "Thin, wide liquidity.",
  },
  groups,
  entries: [
    { id: "baseline", status: "completed", exitCode: 0 },
    { id: "npc-aggression-0p65", status: "completed_with_warnings", exitCode: 0 },
    { id: "npc-aggression-0p35", status: "failed", exitCode: 1, error: "boom" },
  ],
  diagnosticsSummary: { caveats: ["low-run-count"] },
  influenceSummary: {
    groups: [{
      groupId: "npc-aggression",
      targetProfileId: "npc-bad-aggressive-retail",
      knob: "aggression",
      inferenceQuality: "directional-diagnostic-only",
      strongestSignals: [{ metric: "fillCount", delta: 7 }],
      caveats: ["observational-summary; repeat across seeds before scoring policy changes"],
    }],
  },
});
assert.equal(summary.entryCount, 3);
assert.equal(summary.completedCount, 2);
assert.equal(summary.environment.id, "thin-wide-liquidity");
assert.deepEqual(summary.diagnosticsCaveats, ["low-run-count"]);
assert.equal(summary.failedEntries[0].id, "npc-aggression-0p35");
assert.equal(summary.influenceSummaryPath, "/tmp/calibration/actor-influence-summary.json");
assert.equal(summary.influence[0].strongestSignals[0].metric, "fillCount");

const influence = buildActorInfluenceSummary({
  coverage: { reportCount: 3 },
  caveats: ["diagnostics are observational"],
  knobDiagnostics: [{
    knob: "orderRate",
    byProfile: [{
      profileId: "npc-bad-aggressive-retail",
      inferenceQuality: "directional-diagnostic-only",
      values: [
        {
          value: "low",
          observationCount: 5,
          metrics: {
            submittedCommands: { avg: 3 },
            fillCount: { avg: 3 },
          },
        },
        {
          value: "high",
          observationCount: 5,
          metrics: {
            submittedCommands: { avg: 10 },
            fillCount: { avg: 10 },
          },
        },
      ],
    }],
  }],
}, [{
  id: "npc-order-rate",
  description: "NPC taker order-rate sensitivity.",
  targetProfileId: "npc-bad-aggressive-retail",
  knob: "orderRate",
  values: ["low", "high"],
  metricsToWatch: ["submittedCommands", "fillCount"],
}], {
  diagnosticsPath: "/tmp/calibration/actor-diagnostics.json",
  manifestPath: "/tmp/calibration/manifest.json",
});
assert.equal(influence.schemaVersion, "reef.arena.actorInfluenceSummary.v1");
assert.equal(influence.groups[0].rows[0].metrics.submittedCommands, 3);
assert.equal(influence.groups[0].rows[1].metrics.fillCount, 10);
assert.equal(influence.groups[0].metricEffects.find((entry) => entry.metric === "submittedCommands").delta, 7);
assert.equal(influence.groups[0].metricEffects.find((entry) => entry.metric === "fillCount").pctDelta, 233.333333);
assert.equal(influence.groups[0].strongestSignals[0].metric, "submittedCommands");

console.log("arena actor calibration matrix checks passed");
