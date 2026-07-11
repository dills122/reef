import assert from "node:assert/strict";
import { buildDriftBaseline, evaluateReportDrift } from "./lib/scenario-drift.mjs";

const report = {
  throughput: { attemptedPerSecond: 120, acceptedPerSecond: 100 },
  latencyMs: { p95: 20 },
  traceChecks: { checked: 10, pass: 10, fail: 0 },
  rejectTaxonomy: [{ code: "SELF_TRADE_PREVENTION", count: 1 }],
  byActor: { "bot-1": { requests: 1 } },
  byStrategy: { "strategy-1": { requests: 1 } },
  config: { Seed: 7, Mode: "strict-lifecycle" },
  totalRequests: 10,
  totalSuccess: 9,
  totalFailures: 1,
  statusCodes: { 202: 9, 409: 1 },
  byAction: { submit: { requests: 10, success: 9, failures: 1 } },
  quality: { invalidIntentRejectCount: 1, systemFailureCount: 0 },
};

const thresholdCheck = evaluateReportDrift(report, {
  thresholds: {
    minThroughputRps: 100,
    minAcceptedBusinessOpsRps: 90,
    maxP95LatencyMs: 30,
    minTracePassRatePct: 100,
  },
  requiredRejectCodes: ["SELF_TRADE_PREVENTION"],
  requiredAttribution: ["byActor", "byStrategy"],
});
assert.equal(thresholdCheck.pass, true);

const toleranceCheck = evaluateReportDrift(report, {
  thresholds: {
    minThroughputRps: 125,
    minAcceptedBusinessOpsRps: 105,
    performanceTolerancePct: 5,
  },
});
assert.equal(toleranceCheck.pass, true);
assert.deepEqual(toleranceCheck.failures, []);
assert.ok(toleranceCheck.warnings.some((warning) => warning.includes("acceptedPerSecond")));

const outsideToleranceCheck = evaluateReportDrift(report, {
  thresholds: {
    minAcceptedBusinessOpsRps: 110,
    performanceTolerancePct: 5,
  },
});
assert.equal(outsideToleranceCheck.pass, false);
assert.ok(outsideToleranceCheck.failures.some((failure) => failure.includes("effective minimum")));

const baseline = buildDriftBaseline(report, { name: "demo" });
const stableCheck = evaluateReportDrift(report, baseline);
assert.equal(stableCheck.pass, true);

const drifted = { ...report, totalFailures: 2 };
const driftCheck = evaluateReportDrift(drifted, baseline);
assert.equal(driftCheck.pass, false);
assert.ok(driftCheck.failures.some((failure) => failure.includes("totalFailures changed")));
