import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  buildArenaRunArgs,
  compareReports,
  parseArgs,
} from "./run-arena-scoring-calibration-matrix.mjs";

const options = parseArgs([
  "--out-dir=/tmp/reef-matrix",
  "--submit-mode=live",
  "--compartment=vm",
  "--duration-seconds=5",
  "--tick-interval-ms=250",
  "--venue-url=http://127.0.0.1:8080",
  "--arena-admin-url=http://127.0.0.1:8080",
  "--projection-drain-timeout-ms=30000",
  "--seed-reference",
  "--require-projection-drain",
]);
assert.equal(options.outDir, "/tmp/reef-matrix");
assert.equal(options.submitMode, "live");
assert.equal(options.durationSeconds, 5);
assert.equal(options.tickIntervalMs, 250);
assert.equal(options.seedReference, true);
assert.equal(options.requireProjectionDrain, true);

const args = buildArenaRunArgs({
  id: "sprint",
  mode: "packages/scenario-definitions/arena/equity-sprint.v1.json",
}, "/tmp/report.json", options);
assert.deepEqual(args, [
  "scripts/dev/arena-local-tick-run.mjs",
  "--compartment=vm",
  "--submit-mode=live",
  "--mode=packages/scenario-definitions/arena/equity-sprint.v1.json",
  "--report-shape=compact",
  "--out=/tmp/report.json",
  "--duration-seconds=5",
  "--tick-interval-ms=250",
  "--venue-url=http://127.0.0.1:8080",
  "--arena-admin-url=http://127.0.0.1:8080",
  "--seed-reference",
  "--require-projection-drain",
  "--projection-drain-timeout-ms=30000",
]);

const dir = await mkdtemp(path.join(tmpdir(), "reef-arena-matrix-test-"));
const basePath = path.join(dir, "base.json");
const candidatePath = path.join(dir, "candidate.json");
await writeFile(basePath, JSON.stringify(report("base", 1_000_000, 250, ["no-pnl-attribution"])));
await writeFile(candidatePath, JSON.stringify(report("candidate", 1_000_600, 500, ["partial-pnl-attribution"])));

const comparisons = compareReports([
  { id: "base", status: "completed", exitCode: 0, reportPath: basePath },
  { id: "candidate", status: "completed", exitCode: 0, reportPath: candidatePath },
], dir);
assert.equal(comparisons.length, 1);
assert.equal(comparisons[0].baseId, "base");
assert.equal(comparisons[0].candidateId, "candidate");
assert.equal(comparisons[0].topComponentMove.component, "marketInteraction");
assert.deepEqual(comparisons[0].addedFlags, ["partial-pnl-attribution"]);
assert.deepEqual(comparisons[0].removedFlags, ["no-pnl-attribution"]);

const comparisonBody = JSON.parse(await readFile(comparisons[0].comparisonPath, "utf8"));
assert.equal(comparisonBody.scoreDeltas.shadowScore.avgDelta, 600);
assert.equal(comparisonBody.scoreDeltas.components.marketInteraction.avgDelta, 250);

console.log("arena scoring calibration matrix checks passed");

function report(runId, shadowScoreAvg, marketInteractionAvg, flags) {
  return {
    runId,
    mode: { modeId: "equity-sprint", npcDifficultyBuckets: ["benign-noise"] },
    policyEnvelopeHash: `sha256:${runId}`,
    scoringCalibration: {
      formulaVersion: "shadow-score-v1",
      scoringPolicyVersion: "score-v0",
      eligibility: {
        totalBots: 5,
        eligibleCompetitors: 3,
        nonScoringActors: 2,
      },
      difficultyContext: {
        npcDifficultyBuckets: ["benign-noise"],
        difficultyMultiplier: 1,
      },
      scoreDistribution: {
        publicScore: { count: 3, min: 1_000_000, max: 1_001_000, avg: 1_000_500 },
        shadowScore: { count: 3, min: shadowScoreAvg - 100, max: shadowScoreAvg + 100, avg: shadowScoreAvg },
        components: {
          equity: { count: 3, min: 0, max: 0, avg: 0 },
          risk: { count: 3, min: 0, max: 0, avg: 0 },
          conduct: { count: 3, min: 0, max: 0, avg: 0 },
          marketInteraction: { count: 3, min: marketInteractionAvg - 25, max: marketInteractionAvg + 25, avg: marketInteractionAvg },
          difficulty: { count: 3, min: 0, max: 0, avg: 0 },
        },
        diagnostics: {
          fillRatio: { count: 3, min: 0, max: 0, avg: 0 },
          completionRate: { count: 3, min: 1, max: 1, avg: 1 },
        },
      },
      dataQuality: {
        flags,
        publicScoreUnchanged: true,
      },
    },
  };
}
