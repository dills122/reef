import assert from "node:assert/strict";
import {
  aggregateReports,
  canonicalEvidenceSummary,
  canonicalThroughput,
  stableReportFingerprint,
} from "./lib/report-taxonomy.mjs";

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
  latencyMs: { p95: 12, p99: 25 },
  streamDirect: { delta: { ackedDelta: 8 } },
  venueEventMaterializer: { delta: { materializedDelta: 7 } },
  streamAckProjector: { delta: { projectedDelta: 6, afterLag: 2 } },
};

assert.deepEqual(canonicalThroughput(report), {
  attemptedPerSecond: 100,
  acceptedPerSecond: 90,
  completedPerSecond: 0,
  projectedPerSecond: 0,
  visiblePerSecond: 0,
});

assert.deepEqual(canonicalEvidenceSummary(report), {
  attempted: 10,
  accepted: 9,
  directAcked: 8,
  materialized: 7,
  projected: 6,
  lag: 2,
  p95LatencyMs: 12,
  p99LatencyMs: 25,
  rates: {
    attemptedPerSecond: 100,
    acceptedPerSecond: 90,
    directAckedPerSecond: 0,
    materializedPerSecond: 0,
    projectedPerSecond: 0,
  },
  gaps: {
    acceptedToDirectAcked: 1,
    acceptedToMaterialized: 2,
    materializedToProjected: 1,
  },
  projectionFreshness: {
    source: "venue-event-batch-projector",
    freshnessModel: "async read-model projection from durable canonical venue-event materialization",
    materialized: 7,
    projected: 6,
    materializedToProjectedGap: 1,
    lag: 2,
    projectedPerSecond: 0,
    caughtUp: false,
  },
});

assert.deepEqual(
  canonicalEvidenceSummary({
    unitMetrics: {
      attemptedCommands: 100,
      acceptedCommands: 99,
      directAckedCommands: 98,
      durableCanonicalCompletedItems: 97,
      projectedWorkItems: 96,
      projectionLagAfter: 3,
      attemptedCommandsPerSecond: 1000,
      acceptedCommandsPerSecond: 990,
      directAckedCommandsPerSecond: 980,
      durableCanonicalCompletedPerSecond: 970,
      projectedWorkItemsPerSecond: 960,
    },
    latencyMs: { p95: 11, p99: 22 },
  }),
  {
    attempted: 100,
    accepted: 99,
    directAcked: 98,
    materialized: 97,
    projected: 96,
    lag: 3,
    p95LatencyMs: 11,
    p99LatencyMs: 22,
    rates: {
      attemptedPerSecond: 1000,
      acceptedPerSecond: 990,
      directAckedPerSecond: 980,
      materializedPerSecond: 970,
      projectedPerSecond: 960,
    },
    gaps: {
      acceptedToDirectAcked: 1,
      acceptedToMaterialized: 2,
      materializedToProjected: 1,
    },
    projectionFreshness: {
      source: "venue-event-batch-projector",
      freshnessModel: "async read-model projection from durable canonical venue-event materialization",
      materialized: 97,
      projected: 96,
      materializedToProjectedGap: 1,
      lag: 3,
      projectedPerSecond: 960,
      caughtUp: false,
    },
  },
);

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
assert.equal(aggregate.p99LatencyMs.avg, 25);
assert.equal(aggregate.runs[0].evidence.materialized, 7);
