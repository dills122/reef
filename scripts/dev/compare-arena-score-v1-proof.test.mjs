import assert from "node:assert/strict";
import { compareArenaScoreV1Proof } from "./compare-arena-score-v1-proof.mjs";

const base = {
  runId: "proof-a",
  reportShape: "compact",
  mode: {
    modeId: "equity-multi-local",
    scoringPolicyVersion: "score-v1",
  },
  runPlan: {
    durationSeconds: 300,
  },
  status: "completed",
  totals: {
    ticks: 12300,
    failedTicks: 0,
    venueCommands: 2321,
    submittedCommands: 2321,
    completedCommands: 2321,
    rejectedCommands: 0,
    timedOutCommands: 0,
  },
  commandAccounting: {
    draftCommands: 2321,
    submittedCommands: 2321,
    terminalCommands: 2321,
    accountingGap: 0,
  },
  commandStatusSummary: {
    byRoute: {
      "/api/v1/orders/submit": 1979,
      "/api/v1/orders/cancel": 342,
    },
    byFinalStatus: {
      COMPLETED: 2321,
    },
  },
  healthSummary: {
    status: "pass",
    sampleCount: 1350,
    postWarmupSampleCount: 270,
    topOfBookPct: 95.925926,
    depthPct: 95.925926,
    crossedBookCount: 0,
    lockedBookCount: 0,
    emptyBookCount: 55,
  },
  marketQualitySummary: {
    status: "pass",
    failures: [],
  },
  executionSummary: {
    fillCount: 2010,
    byInstrument: {
      AAPL: { fillCount: 402, filledQuantity: 402, filledNotional: 40200, avgFillPrice: 100 },
    },
    byRole: {
      competitor: { fillCount: 10, filledQuantity: 10, filledNotional: 2400, avgFillPrice: 240 },
    },
  },
  scoringCalibration: {
    mode: "public-score-v1-with-shadow-calibration",
    formulaVersion: "score-v1",
    scoringPolicyVersion: "score-v1",
    dataQuality: {
      flags: [],
      publicScoreMismatchCount: 0,
      pnlAvailableCount: 11,
      fillCount: 10,
    },
    scoreDistribution: {
      publicScore: {
        count: 11,
        min: 974981,
        max: 1000000,
        avg: 977270.545455,
      },
    },
  },
  leaderboard: [
    { rank: 1, botId: "custom-technical-indicator", score: 1000000, disqualified: false },
    { rank: 2, botId: "custom-passive-aapl-sell", score: 975014, disqualified: false },
  ],
  botResults: [
    {
      botId: "custom-technical-indicator",
      score: 1000000,
      disqualified: false,
      venueCommands: 0,
      conductMetrics: { submitCommands: 0, cancelCommands: 0 },
      tradingMetrics: { commands: { submitted: 0, byRoute: {} } },
    },
    {
      botId: "custom-passive-aapl-sell",
      score: 975014,
      disqualified: false,
      venueCommands: 1,
      conductMetrics: { submitCommands: 1, cancelCommands: 0 },
      tradingMetrics: { commands: { submitted: 1, byRoute: { "/api/v1/orders/submit": 1 } } },
    },
  ],
};

const withinHealthTolerance = {
  ...base,
  runId: "proof-b",
  healthSummary: {
    ...base.healthSummary,
    topOfBookPct: 96,
    depthPct: 96,
    emptyBookCount: 54,
  },
};

const passingComparison = compareArenaScoreV1Proof(base, withinHealthTolerance);
assert.equal(passingComparison.status, "pass");
assert.equal(passingComparison.deterministicHash.matched, true);
assert.equal(passingComparison.health.delta.emptyBookCount, -1);
assert.equal(passingComparison.health.status, "pass");

const scoreDrift = {
  ...withinHealthTolerance,
  leaderboard: [
    { rank: 1, botId: "custom-technical-indicator", score: 999999, disqualified: false },
    { rank: 2, botId: "custom-passive-aapl-sell", score: 975014, disqualified: false },
  ],
};
const scoreDriftComparison = compareArenaScoreV1Proof(base, scoreDrift);
assert.equal(scoreDriftComparison.status, "fail");
assert.equal(scoreDriftComparison.exactMatches.leaderboard, false);
assert.match(scoreDriftComparison.failures.join("\n"), /leaderboard differs/);

const healthDrift = {
  ...withinHealthTolerance,
  healthSummary: {
    ...withinHealthTolerance.healthSummary,
    topOfBookPct: 90,
    depthPct: 90,
    emptyBookCount: 120,
  },
};
const healthDriftComparison = compareArenaScoreV1Proof(base, healthDrift);
assert.equal(healthDriftComparison.status, "fail");
assert.match(healthDriftComparison.failures.join("\n"), /emptyBookCount delta/);
assert.match(healthDriftComparison.failures.join("\n"), /topOfBookPct delta/);

console.log("arena score-v1 proof comparison checks passed");
