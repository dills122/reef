import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  actorCalibrationCliSummary,
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
  "--no-baseline",
]);
assert.equal(options.outDir, "/tmp/calibration");
assert.deepEqual(options.groups, ["npc-aggression", "mm-quote-size"]);
assert.equal(options.includeBaseline, false);

const summary = actorCalibrationCliSummary({
  manifestPath: "/tmp/calibration/manifest.json",
  diagnosticsPath: "/tmp/calibration/actor-diagnostics.json",
  submitMode: "dry-run",
  groups,
  entries: [
    { id: "baseline", status: "completed" },
    { id: "npc-aggression-0p35", status: "failed", exitCode: 1, error: "boom" },
  ],
  diagnosticsSummary: { caveats: ["low-run-count"] },
});
assert.equal(summary.entryCount, 2);
assert.equal(summary.completedCount, 1);
assert.deepEqual(summary.diagnosticsCaveats, ["low-run-count"]);
assert.equal(summary.failedEntries[0].id, "npc-aggression-0p35");

console.log("arena actor calibration matrix checks passed");
