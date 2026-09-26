import assert from "node:assert/strict";
import { test } from "node:test";
import { buildDownstreamFreshnessGate } from "./downstream-freshness.mjs";

const upstreamChecks = ["acceptedCountMatchesReport", "acceptedSourceExclusive", "durableIntakeJoined", "materializerMembershipJoined", "checksumMembershipComplete", "canonicalCommitObserved", "sourceCommitObserved", "sourceCommitFrontiersComplete", "canonicalSourceTimingComplete", "clocksValid"];
const downstreamChecks = ["databaseGenerationStable", "completeCohortResidence", "upstreamCohortAuthoritative", "downstreamInstrumentationEnabled", "coveringMarkersPresent", "stageMarkerIdentityMatches", "lifecycleMarkerCoversExclusiveCohort", "marketDataMarkerCoversExclusiveCohort", "finalQueuesDrained", "postCommitObservationsComplete", "callerTopologyVisibleAndIdle", "clocksValid", "diagnosticSamplerComplete", "projectedCohortReconciled"];
const stages = ["sourceToCanonical", "sourceToLifecycle", "sourceToMarketData"];
function report() {
  const callers = { calls: 10, completed: 10, failed: 0, active: 0, maxConcurrent: 1, callerCount: 1, callers: { worker: {} } };
  return {
    totalRequests: 30000, totalSuccess: 30000, totalFailures: 0,
    durationSeconds: 300, startedAt: "2026-09-24T01:00:00.000Z", finishedAt: "2026-09-24T01:05:00.000Z",
    config: { Duration: 300e9, RatePerSecond: 100, RateSchedule: "precise" },
    loadSchedule: { mode: "precise", targetRatePerSecond: 100, targetRequests: 30000, scheduled: 30000, enqueued: 30000, dropped: 0, completed: 30000, scheduleDeficit: 0, completionDeficit: 0, enqueuedPerSecond: 100, completedPerSecond: 100, completionToTargetPct: 100 },
    upstreamSourceCohort: { pass: true, authority: "exclusive-kafka-offset-frontier-v1", checks: Object.fromEntries(upstreamChecks.map(k => [k,true])), totals: { accepted: "30000", directAcked: "30000", materializedMembership: "30000" } },
    downstreamProjectionCohort: { pass: true, authority: "post-commit-downstream-covering-marker-v1", checks: Object.fromEntries(downstreamChecks.map(k => [k,true])), markers: { lifecycle: { sourceProjectionName: "runtime-normalized-submit" }, marketData: { sourceProjectionName: "runtime-normalized-submit" } }, callers: { lifecycle: callers, marketData: structuredClone(callers) }, cohortResidence: { pass: true, authority: "source-membership-to-sampled-commit-upper-bound-v1", commandCount: "30000", stages: Object.fromEntries(stages.map(k => [k, { commandCount: "30000", meanMs: 1000, p95Ms: 5000, p99Ms: 10000, maxMs: 30000 }])) } },
    streamAckProjector: { delta: { projectedDelta: 30000, afterLag: 0 }, after: { projectionName: "runtime-normalized-submit", projectors: [{ orderLifecycleProjectorEnabled: true, marketDataProjectorEnabled: true, ...Object.fromEntries(["orderLifecycleProjector", "marketDataProjector"].map(k => [k, { sourceProjectionName: "runtime-normalized-submit", instrumentation: { enabled: true, stageEnabled: true, dirtyQueues: { orderLifecyclePending: 0, marketDataPending: 0 } } }])) }] } },
  };
}

test("inclusive frozen observation bounds pass for complete weighted cohort", () => {
  const result = buildDownstreamFreshnessGate(report());
  assert.equal(result.pass, true);
  assert.deepEqual(result.limitsMs, { p95: 5000, p99: 10000, max: 30000 });
});

test("any stage exceeding any threshold fails regardless of caller options", () => {
  for (const stage of stages) for (const [field,value] of [["p95Ms",5001],["p99Ms",10001],["maxMs",30001]]) {
    const r=report(); r.downstreamProjectionCohort.cohortResidence.stages[stage][field]=value;
    assert.equal(buildDownstreamFreshnessGate(r, { p95: Infinity, p99: Infinity, max: Infinity }).pass,false);
  }
});

test("missing malformed unordered or incomplete residence fails closed", () => {
  for (const stage of stages) for (const field of ["meanMs","p95Ms","p99Ms","maxMs","commandCount"]) for (const value of [undefined,null,false,"",-1,Infinity,"NaN", "99"]) {
    const r=report(); r.downstreamProjectionCohort.cohortResidence.stages[stage][field]=value;
    assert.equal(buildDownstreamFreshnessGate(r).pass,false,`${stage}.${field}=${value}`);
  }
  for (const change of [r=>{delete r.downstreamProjectionCohort.cohortResidence;},r=>{r.downstreamProjectionCohort.cohortResidence.stages.sourceToCanonical.p95Ms=11000;},r=>{r.downstreamProjectionCohort.cohortResidence.stages.sourceToLifecycle.meanMs=30001;},r=>{r.downstreamProjectionCohort.cohortResidence.commandCount="99";}]) {
    const r=report(); change(r); assert.equal(buildDownstreamFreshnessGate(r).pass,false);
  }
});

test("authority checks final queues idle callers and scheduled demand are mandatory", () => {
  const mutations=[
    r=>{r.upstreamSourceCohort.pass=false;},r=>{r.downstreamProjectionCohort.authority="tail-only";},r=>{r.upstreamSourceCohort.authority="other";},r=>{r.downstreamProjectionCohort.cohortResidence.authority="exact-time";},
    r=>{delete r.downstreamProjectionCohort.checks.finalQueuesDrained;},r=>{delete r.upstreamSourceCohort.checks.clocksValid;},r=>{r.upstreamSourceCohort.totals.accepted="99";},
    r=>{r.streamAckProjector.after.projectors[0].orderLifecycleProjector.instrumentation.dirtyQueues.orderLifecyclePending=1;},
    r=>{delete r.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.dirtyQueues;},
    r=>{delete r.downstreamProjectionCohort.callers.lifecycle.active;},r=>{r.downstreamProjectionCohort.callers.marketData.active=1;},
    r=>{delete r.streamAckProjector.delta.afterLag;},r=>{delete r.loadSchedule;},
    r=>{r.loadSchedule.scheduled=101;r.loadSchedule.dropped=1;},
    r=>{r.loadSchedule.targetRequests=200;r.loadSchedule.completionToTargetPct=50;},
  ];
  for (const mutate of mutations) { const r=report(); mutate(r); assert.equal(buildDownstreamFreshnessGate(r).pass,false); }
});


test("sustained gate rejects short missing or fabricated duration evidence", () => {
  for (const mutate of [
    r => { r.config.Duration = 60e9; r.config.RatePerSecond = 500; r.loadSchedule.targetRatePerSecond = 500; r.durationSeconds = 60; r.finishedAt = "2026-09-24T01:01:00.000Z"; },
    r => { r.config.Duration = 60e9; },
    r => { r.durationSeconds = 299.999; r.finishedAt = "2026-09-24T01:04:59.999Z"; },
    r => { delete r.durationSeconds; }, r => { r.durationSeconds = null; },
    r => { r.durationSeconds = "300"; }, r => { r.durationSeconds = Infinity; },
    r => { delete r.startedAt; }, r => { r.startedAt = null; }, r => { r.startedAt = "invalid"; },
    r => { delete r.finishedAt; }, r => { r.finishedAt = "invalid"; },
    r => { r.finishedAt = "2026-09-24T01:04:00.000Z"; },
    r => { r.finishedAt = "2026-09-24T00:55:00.000Z"; },
    r => { r.durationSeconds = 301; },
    r => { r.finishedAt = "2026-09-24T01:05:00.003Z"; },
  ]) {
    const r = report(); mutate(r);
    const gate = buildDownstreamFreshnessGate(r);
    assert.equal(gate.pass, false);
    assert.equal(gate.checks.sustainedDurationComplete, false);
  }
});

test("five-minute boundary and submillisecond Go duration precision are accepted", () => {
  for (const [durationSeconds, finishedAt] of [
    [300, "2026-09-24T01:05:00.000Z"],
    [300.0009, "2026-09-24T01:05:00.000Z"],
    [300.0019, "2026-09-24T01:05:00.003Z"],
    [300, "2026-09-24T01:05:00.002Z"],
  ]) {
    const r = report(); Object.assign(r, { durationSeconds, finishedAt });
    assert.equal(buildDownstreamFreshnessGate(r).pass, true);
  }
});

test("market read metadata binds to canonical and both covering marker source namespaces", () => {
  for (const mutate of [
    r=>{r.streamAckProjector.after.projectors[0].marketDataProjector.sourceProjectionName="runtime-normalized-venue-outcomes";},
    r=>{delete r.streamAckProjector.after.projectors[0].marketDataProjector.sourceProjectionName;},
    r=>{r.downstreamProjectionCohort.markers.marketData.sourceProjectionName="other";},
    r=>{r.downstreamProjectionCohort.markers.lifecycle.sourceProjectionName="other";},
    r=>{delete r.streamAckProjector.after.projectionName;},
  ]) {
    const r=report(); mutate(r);
    assert.equal(buildDownstreamFreshnessGate(r).pass,false);
  }
});

test("matching explicit nondefault namespace remains valid", () => {
  const r=report();
  r.streamAckProjector.after.projectionName="retained-custom-namespace";
  r.streamAckProjector.after.projectors[0].marketDataProjector.sourceProjectionName="retained-custom-namespace";
  r.downstreamProjectionCohort.markers.lifecycle.sourceProjectionName="retained-custom-namespace";
  r.downstreamProjectionCohort.markers.marketData.sourceProjectionName="retained-custom-namespace";
  assert.equal(buildDownstreamFreshnessGate(r).pass,true);
});
