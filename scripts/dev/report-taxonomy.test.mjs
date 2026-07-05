import assert from "node:assert/strict";
import { aggregateReports, canonicalThroughput, stableReportFingerprint } from "./lib/report-taxonomy.mjs";

const report = {
  throughputRps: 100,
  acceptedBusinessOpsRps: 90,
  totalRequests: 10,
  totalSuccess: 9,
  totalFailures: 1,
  config: { Seed: 4242, Mode: "strict-lifecycle", ScenarioID: "demo" },
  statusCodes: { 202: 9, 409: 1 },
  byAction: { submit: { requests: 10, success: 9, failures: 1 } },
  rejectTaxonomy: [{ code: "INVALID_STATE", count: 1, percentOfFailures: 100 }],
  quality: { invalidIntentRejectCount: 1, systemFailureCount: 0 },
  traceChecks: { checked: 5, pass: 5, fail: 0 },
  latencyMs: { p95: 12 },
};

assert.deepEqual(canonicalThroughput(report), {
  attemptedPerSecond: 100,
  acceptedPerSecond: 90,
  completedPerSecond: 0,
  projectedPerSecond: 0,
  visiblePerSecond: 0,
});

assert.deepEqual(stableReportFingerprint(report), {
  scenarioId: "demo",
  seed: 4242,
  mode: "strict-lifecycle",
  totalRequests: 10,
  totalSuccess: 9,
  totalFailures: 1,
  statusCodes: { 202: 9, 409: 1 },
  byAction: { submit: { requests: 10, success: 9, failures: 1 } },
  rejectTaxonomy: [{ code: "INVALID_STATE", count: 1 }],
  quality: { invalidIntentRejectCount: 1, systemFailureCount: 0 },
  traceChecks: { checked: 5, pass: 5, fail: 0 },
});

const aggregate = aggregateReports([
  { path: "/tmp/a.json", data: report },
  { path: "/tmp/b.json", data: { ...report, throughputRps: 80, acceptedBusinessOpsRps: 70 } },
]);

assert.equal(aggregate.runCount, 2);
assert.equal(aggregate.totals.requests, 20);
assert.equal(aggregate.throughput.attemptedPerSecond.min, 80);
assert.equal(aggregate.throughput.attemptedPerSecond.max, 100);
assert.equal(aggregate.throughput.acceptedPerSecond.avg, 80);
