import assert from "node:assert/strict";
import test from "node:test";
import {
  buildDownstreamProjectionCohort,
  downstreamInstrumentationDrained,
} from "./downstream-cohort.mjs";

test("reconciles lifecycle and market covering markers to the exclusive source cohort", () => {
  const cohort = buildDownstreamProjectionCohort(exactReport());

  assert.equal(cohort.schemaVersion, 1);
  assert.equal(cohort.authority, "post-commit-downstream-covering-marker-v1");
  assert.equal(cohort.pass, true);
  assert.deepEqual(cohort.tailDrainObservation, {
    sourceWorkFinishedAt: "2026-08-24T18:00:00Z",
    lifecyclePostCommitObservedAt: "2026-08-24T18:00:00.250Z",
    marketDataPostCommitObservedAt: "2026-08-24T18:00:00.400Z",
    sourceToLifecycleMs: 250,
    sourceToMarketDataMs: 400,
    lifecycleToMarketDataMs: 150,
  });
  assert.ok(Object.values(cohort.checks).every(Boolean));
});

test("uses exact composite sequences for nonzero partitions", () => {
  const report = exactReport();
  report.streamAckProjector.after.projectors[0].orderLifecycleProjector.instrumentation.coverage.lastMarker
    .sourceWatermarks[1].lastPartitionSequence = "562949953421333";

  const cohort = buildDownstreamProjectionCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.checks.lifecycleMarkerCoversExclusiveCohort, false);
});

test("rejects mismatched lifecycle and market marker identities", () => {
  const report = exactReport();
  report.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.coverage.lastMarker.markerId = "other";

  const cohort = buildDownstreamProjectionCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.checks.stageMarkerIdentityMatches, false);
});

test("rejects missing post-commit authority and hidden active callers", () => {
  const report = exactReport();
  const instrumentation = report.streamAckProjector.after.projectors[0].orderLifecycleProjector.instrumentation;
  instrumentation.coverage.lastMarker.postCommitObserved = false;
  instrumentation.callerStats.active = 1;

  const cohort = buildDownstreamProjectionCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.checks.postCommitObservationsComplete, false);
  assert.equal(cohort.checks.callerTopologyVisibleAndIdle, false);
});

test("rejects diagnostic sampling gaps beyond the one-second tolerance", () => {
  const report = exactReport();
  report.downstreamDiagnosticSampler.maxGapMs = 2501;

  const cohort = buildDownstreamProjectionCohort(report);

  assert.equal(cohort.pass, false);
  assert.equal(cohort.checks.diagnosticSamplerComplete, false);
});

test("sampler authority keeps frozen cadence and gap limits despite caller configuration", () => {
  for (const overrides of [
    { configuredIntervalMs: 5000, maxAllowedGapMs: 10000, maxGapMs: 5000 },
    { configuredIntervalMs: 2000, maxAllowedGapMs: 4000, maxGapMs: 1000 },
    { configuredIntervalMs: 500, maxAllowedGapMs: 2000 },
    { maxAllowedGapMs: 10000, maxGapMs: 2500 },
    { maxAllowedGapMs: 1000 },
  ]) {
    const report = exactReport();
    Object.assign(report.downstreamDiagnosticSampler, overrides);
    const cohort = buildDownstreamProjectionCohort(report);
    assert.equal(cohort.checks.diagnosticSamplerComplete, false, JSON.stringify(overrides));
    assert.equal(cohort.pass, false);
  }
});

test("sampler authority requires every metric explicitly valid", () => {
  for (const field of ["configuredIntervalMs", "sampleCount", "successfulSampleCount", "maxGapMs", "maxAllowedGapMs"]) {
    for (const value of [undefined, null, "", "garbage", NaN, Infinity, -1, false, [], {}]) {
      const report = exactReport();
      report.downstreamDiagnosticSampler[field] = value;
      assert.equal(buildDownstreamProjectionCohort(report).checks.diagnosticSamplerComplete, false, `${field}=${String(value)}`);
    }
  }
  const fractionalCounts = exactReport();
  fractionalCounts.downstreamDiagnosticSampler.sampleCount = 2.5;
  fractionalCounts.downstreamDiagnosticSampler.successfulSampleCount = 2.5;
  assert.equal(buildDownstreamProjectionCohort(fractionalCounts).pass, false);
});

test("sampler accepts exact gap boundary but rejects failed samples and over-boundary gap", () => {
  const report = exactReport();
  report.downstreamDiagnosticSampler.maxGapMs = 2000;
  assert.equal(buildDownstreamProjectionCohort(report).pass, true);
  report.downstreamDiagnosticSampler.maxGapMs = 2000.1;
  assert.equal(buildDownstreamProjectionCohort(report).pass, false);
  report.downstreamDiagnosticSampler.maxGapMs = 1000;
  report.downstreamDiagnosticSampler.successfulSampleCount = 3;
  assert.equal(buildDownstreamProjectionCohort(report).pass, false);
});

test("accepts either ordering for independently sampled stage observations", () => {
  const report = exactReport();
  report.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.coverage
    .lastMarker.postCommitObservedAt = "2026-08-24T18:00:00.225Z";
  report.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.coverage
    .lastMarker.databaseSnapshotAt = "2026-08-24T18:00:00.210Z";

  const cohort = buildDownstreamProjectionCohort(report);

  assert.equal(cohort.pass, true);
  assert.equal(cohort.checks.clocksValid, true);
  assert.equal(cohort.tailDrainObservation.lifecycleToMarketDataMs, -25);
});

test("drain authority waits for idle callers and matching full-frontier markers", () => {
  const after = exactReport().streamAckProjector.after;

  assert.equal(downstreamInstrumentationDrained(after), true);

  after.projectors[0].marketDataProjector.instrumentation.callerStats.active = 1;
  assert.equal(downstreamInstrumentationDrained(after), false);
  after.projectors[0].marketDataProjector.instrumentation.callerStats.active = 0;

  after.projectors[0].marketDataProjector.instrumentation.coverage.lastMarker.markerId = "stale";
  assert.equal(downstreamInstrumentationDrained(after), false);
});

test("drain authority rejects partial-partition markers", () => {
  const after = exactReport().streamAckProjector.after;
  after.projectors[0].orderLifecycleProjector.instrumentation.coverage.lastMarker.sourceWatermarks.pop();
  after.projectors[0].marketDataProjector.instrumentation.coverage.lastMarker.sourceWatermarks.pop();

  assert.equal(downstreamInstrumentationDrained(after), false);
});

function exactReport() {
  const marker = {
    markerId: "marker-1",
    databaseGeneration: "2026-08-24T17:00:00Z",
    sourceProjectionName: "runtime-normalized-venue-outcomes",
    sourceWatermarks: [
      { partition: 0, lastPartitionSequence: "13" },
      { partition: 2, lastPartitionSequence: "562949953421334" },
    ],
    databaseSnapshotAt: "2026-08-24T18:00:00.200Z",
    postCommitObservedAt: "2026-08-24T18:00:00.250Z",
    postCommitObserved: true,
    callerCount: 2,
    callers: ["market-data-projector", "order-lifecycle-projector"],
  };
  const instrumentation = (stage, markerOverride = {}) => ({
    enabled: true,
    stageEnabled: true,
    sampleIntervalMs: 1000,
    dirtyQueues: {
      databaseGeneration: "2026-08-24T17:00:00Z",
      orderLifecyclePending: 0,
      marketDataPending: 0,
      databaseSnapshotAt: "2026-08-24T18:00:00.200Z",
    },
    callerStats: {
      calls: 20,
      completed: 20,
      failed: 0,
      active: 0,
      maxConcurrent: 1,
      callerCount: stage === "market" ? 1 : 2,
      callers: stage === "market"
        ? { "market-data-projector": 20 }
        : { "market-data-projector": 10, "order-lifecycle-projector": 10 },
    },
    coverage: {
      databaseGeneration: "2026-08-24T17:00:00Z",
      generationGuardFailures: 0,
      markerCount: 1,
      clockGuardFailures: 0,
      lastMarker: { ...marker, ...markerOverride },
    },
  });
  return {
    materializerTiming: {
      before: [{enabled:true,instanceId:"m1",lastSequence:"0",dropped:"0",duplicate:"0",invalid:"0",records:[]}],
      after: [{enabled:true,instanceId:"m1",lastSequence:"2",dropped:"0",duplicate:"0",invalid:"0",records:[
        {sequence:"1",batchId:"b1",payloadChecksum:"a".repeat(64),commandStream:"commands",partition:0,commandCount:"3",streamSequences:["11","12","13"],workFinishedAt:"2026-08-24T18:00:00Z",canonicalCommitObservedAt:"2026-08-24T18:00:00.100Z"},
        {sequence:"2",batchId:"b2",payloadChecksum:"b".repeat(64),commandStream:"commands",partition:2,commandCount:"2",streamSequences:["562949953421333","562949953421334"],workFinishedAt:"2026-08-24T18:00:00Z",canonicalCommitObservedAt:"2026-08-24T18:00:00.100Z"}
      ]}],
    },
    totalSuccess: 5,
    upstreamSourceCohort: {
      pass: true,
      partitions: [
        { partition: 0, startOffsetInclusive: "10", endOffsetExclusive: "13", accepted: "3" },
        { partition: 2, startOffsetInclusive: "20", endOffsetExclusive: "22", accepted: "2" },
      ],
    },
    venueEventMaterializer: {
      after: { metrics: { lastSourceWorkFinishedAt: "2026-08-24T18:00:00Z" } },
    },
    streamAckProjector: {
      before: {projectors:[{
        orderLifecycleProjectorEnabled:true, marketDataProjectorEnabled:true,
        orderLifecycleProjector:{instrumentation:instrumentation("lifecycle")},
        marketDataProjector:{instrumentation:instrumentation("market")},
      }]},
      delta: { projectedDelta: 5, afterLag: 0 },
      after: {
        lag: 0,
        watermarks: [
          { partition: 0, lastPartitionSequence: 13, lag: 0 },
          { partition: 2, lastPartitionSequence: 562949953421334, lag: 0 },
        ],
        projectors: [
          {
            index: 0,
            orderLifecycleProjectorEnabled: true,
            marketDataProjectorEnabled: true,
            orderLifecycleProjector: { instrumentation: instrumentation("lifecycle") },
            marketDataProjector: {
              instrumentation: instrumentation("market", {
                postCommitObservedAt: "2026-08-24T18:00:00.400Z",
                databaseSnapshotAt: "2026-08-24T18:00:00.350Z",
                callerCount: 1,
                callers: ["market-data-projector"],
              }),
            },
          },
        ],
      },
    },
    downstreamDiagnosticSampler: {
      configuredIntervalMs: 1000,
      sampleCount: 4,
      successfulSampleCount: 4,
      maxGapMs: 1000,
      maxAllowedGapMs: 2000,
    },
  };
}

for (const value of [undefined, null, "garbage", -1, 0.5, "", Number.NaN]) {
  test(`rejects absent or invalid required queue/failure/clock metrics: ${String(value)}`, () => {
    for (const stage of ["orderLifecycleProjector", "marketDataProjector"]) {
      for (const [section, field] of [["dirtyQueues", "orderLifecyclePending"], ["dirtyQueues", "marketDataPending"], ["callerStats", "failed"], ["callerStats", "active"], ["coverage", "clockGuardFailures"]]) {
        const report = exactReport();
        report.streamAckProjector.after.projectors[0][stage].instrumentation[section][field] = value;
        assert.equal(buildDownstreamProjectionCohort(report).pass, false, `${stage}.${section}.${field}`);
      }
    }
  });
}

test("database generation changes or missing guards invalidate cohort", () => {
  for (const change of [
    r => delete r.streamAckProjector.before,
    r => r.streamAckProjector.before.projectors[0].orderLifecycleProjector.instrumentation.coverage.databaseGeneration = "old",
    r => delete r.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.coverage.generationGuardFailures,
    r => r.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.coverage.generationGuardFailures = 1,
    r => r.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.dirtyQueues.databaseGeneration = "new",
    r => r.streamAckProjector.after.projectors[0].marketDataProjector.instrumentation.coverage.lastMarker.databaseGeneration = "new",
  ]) {
    const report = exactReport();change(report);
    assert.equal(buildDownstreamProjectionCohort(report).pass, false);
  }
});
