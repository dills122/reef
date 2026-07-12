import assert from "node:assert/strict";
import { compareArenaScoringCalibration } from "./compare-arena-scoring-calibration.mjs";

const base = {
  runId: "base-run",
  mode: { modeId: "equity-sprint", npcDifficultyBuckets: ["benign-noise"] },
  policyEnvelopeHash: "sha256:base",
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
      shadowScore: { count: 3, min: 1_000_100, max: 1_002_000, avg: 1_001_000 },
      components: {
        equity: { count: 3, min: -10, max: 20, avg: 5 },
        risk: { count: 3, min: -100, max: 0, avg: -50 },
        conduct: { count: 3, min: 0, max: 0, avg: 0 },
        marketInteraction: { count: 3, min: 100, max: 500, avg: 300 },
        difficulty: { count: 3, min: 0, max: 0, avg: 0 },
      },
      diagnostics: {
        fillRatio: { count: 3, min: 0.1, max: 0.5, avg: 0.3 },
        completionRate: { count: 3, min: 0.8, max: 1, avg: 0.9 },
      },
    },
    dataQuality: {
      flags: ["no-pnl-attribution"],
      publicScoreUnchanged: true,
    },
  },
};

const candidate = {
  runId: "candidate-run",
  mode: { modeId: "equity-sprint", npcDifficultyBuckets: ["toxic-momentum"] },
  policyEnvelopeHash: "sha256:candidate",
  scoringCalibration: {
    formulaVersion: "shadow-score-v1",
    scoringPolicyVersion: "score-v0",
    eligibility: {
      totalBots: 6,
      eligibleCompetitors: 4,
      nonScoringActors: 2,
    },
    difficultyContext: {
      npcDifficultyBuckets: ["toxic-momentum"],
      difficultyMultiplier: 1.1,
    },
    scoreDistribution: {
      publicScore: { count: 4, min: 1_000_100, max: 1_002_000, avg: 1_001_000 },
      shadowScore: { count: 4, min: 1_000_200, max: 1_004_000, avg: 1_002_500 },
      components: {
        equity: { count: 4, min: -5, max: 40, avg: 20 },
        risk: { count: 4, min: -200, max: 0, avg: -75 },
        conduct: { count: 4, min: 0, max: 0, avg: 0 },
        marketInteraction: { count: 4, min: 200, max: 1_000, avg: 700 },
        difficulty: { count: 4, min: 10, max: 70, avg: 40 },
      },
      diagnostics: {
        fillRatio: { count: 4, min: 0.2, max: 0.8, avg: 0.5 },
        completionRate: { count: 4, min: 0.9, max: 1, avg: 0.95 },
      },
    },
    dataQuality: {
      flags: ["partial-pnl-attribution"],
      publicScoreUnchanged: true,
    },
  },
};

const comparison = compareArenaScoringCalibration(base, candidate);
assert.equal(comparison.schemaVersion, "reef.arena.scoringCalibrationComparison.v1");
assert.equal(comparison.formulaVersionChanged, false);
assert.equal(comparison.eligibilityDelta.eligibleCompetitors, 1);
assert.equal(comparison.difficultyMultiplierDelta, 0.1);
assert.equal(comparison.scoreDeltas.shadowScore.avgDelta, 1_500);
assert.equal(comparison.scoreDeltas.components.marketInteraction.avgDelta, 400);
assert.equal(comparison.topComponentMove.component, "marketInteraction");
assert.deepEqual(comparison.dataQuality.addedFlags, ["partial-pnl-attribution"]);
assert.deepEqual(comparison.dataQuality.removedFlags, ["no-pnl-attribution"]);
assert.equal(comparison.dataQuality.publicScoreStillUnchanged, true);

console.log("arena scoring calibration comparison checks passed");
