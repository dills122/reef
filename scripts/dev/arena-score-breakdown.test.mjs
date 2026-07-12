import assert from "node:assert/strict";
import {
  buildScoreBreakdown,
  buildScoreContext,
} from "./lib/arena-score-breakdown.mjs";

const context = buildScoreContext({
  scoringPolicyVersion: "score-v0",
  npcDifficultyBuckets: ["benign-noise", "toxic-momentum"],
});

const eligible = {
  botId: "custom-balanced",
  actorClass: "competitor",
  actorProfile: { scoreEffect: "eligible-for-score" },
  scoreEligible: true,
  score: 1_000_750,
  failedTicks: 0,
  freezeCount: 0,
  operationalPauseCount: 0,
  tradingMetrics: {
    commands: {
      submitted: 10,
      completed: 8,
      failed: 0,
      rejected: 0,
      timedOut: 0,
    },
    orderFlow: {
      grossSubmittedQuantity: 20,
      grossSubmittedNotional: 2_000,
    },
    executions: {
      fillCount: 3,
      filledQuantity: 10,
      grossNotional: 1_000,
    },
    pnl: {
      available: true,
      total: 50,
      finalEquityDiagnostic: 1_000_050,
      markPriceSource: "test-mid",
    },
    inventory: {
      grossNotional: 200,
      netQuantityByInstrument: { AAPL: 1, MSFT: -0.5 },
      markPriceByInstrument: { AAPL: 100, MSFT: 200 },
      markPriceSource: "test-mid",
    },
  },
  conductMetrics: {
    cancelReplaceRatio: 0.25,
    invalidIntentRate: 0,
    timeoutRate: 0,
    maxActionsPerTick: 1,
    maxVenueCommandsPerTick: 1,
    freezeCount: 0,
  },
};

const eligibleBreakdown = buildScoreBreakdown(eligible, context);
assert.equal(eligibleBreakdown.formulaVersion, "shadow-score-v1");
assert.equal(eligibleBreakdown.scoreEligible, true);
assert.equal(eligibleBreakdown.publicScore, eligible.score);
assert.deepEqual(eligibleBreakdown.components, {
  baseline: 1_000_000,
  equity: 50,
  risk: -1_502,
  conduct: 0,
  marketInteraction: 7_025,
  difficulty: 557,
});
assert.equal(eligibleBreakdown.shadowScore, 1_006_130);
assert.equal(eligibleBreakdown.diagnostics.fillRatio, 0.5);
assert.equal(eligibleBreakdown.diagnostics.completionRate, 0.8);
assert.equal(eligibleBreakdown.diagnostics.pnlPerExecutedNotionalBps, 500);
assert.equal(eligibleBreakdown.diagnostics.inventoryExposureRatio, 0.1);
assert.equal(eligibleBreakdown.diagnostics.inventoryConcentration, 0.5);
assert.equal(eligibleBreakdown.componentDetails.risk.inventoryExposurePenalty, 1_500);
assert.equal(eligibleBreakdown.componentDetails.marketInteraction.fillEfficiencyScore, 2_500);

const diagnosticOnly = buildScoreBreakdown({
  ...eligible,
  actorClass: "house_market_maker",
  actorProfile: { scoreEffect: "diagnostic-only" },
}, context);
assert.equal(diagnosticOnly.scoreEligible, false);
assert.equal(diagnosticOnly.publicScore, null);
assert.equal(diagnosticOnly.shadowScore, null);
assert.equal(diagnosticOnly.scoringMode, "diagnostic-only");
assert.equal(diagnosticOnly.components.equity, 0);
assert.equal(diagnosticOnly.diagnostics.fillRatio, 0.5);

console.log("arena score breakdown checks passed");
