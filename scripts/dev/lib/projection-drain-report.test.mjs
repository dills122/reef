import assert from "node:assert/strict";

import { buildProjectionDrainReport } from "./projection-drain-report.mjs";

const report = buildProjectionDrainReport({
  startedAt: "2026-08-20T12:00:00.000Z",
  finishedAt: "2026-08-20T12:00:02.000Z",
  projectorUrls: ["http://projector-0", "http://projector-1"],
  samples: [
    sample("2026-08-20T12:00:00.000Z", 1_000, 4_000, 3_000),
    sample("2026-08-20T12:00:01.000Z", 400, 4_000, 3_600),
    sample("2026-08-20T12:00:02.000Z", 0, 4_000, 4_000),
  ],
});

assert.equal(report.schemaVersion, "reef.projectionDrainBenchmark.v1");
assert.equal(report.result.initialLag, 1_000);
assert.equal(report.result.finalLag, 0);
assert.equal(report.result.drainedWorkItems, 1_000);
assert.equal(report.result.elapsedMs, 2_000);
assert.equal(report.result.drainRps, 500);
assert.equal(report.result.canonicalCeilingStable, true);
assert.equal(report.result.lagAreaWorkItemSeconds, 900);
assert.deepEqual(report.failures, []);

const movingBacklog = buildProjectionDrainReport({
  startedAt: "2026-08-20T12:00:00.000Z",
  finishedAt: "2026-08-20T12:00:01.000Z",
  projectorUrls: ["http://projector-0"],
  samples: [
    sample("2026-08-20T12:00:00.000Z", 100, 1_000, 900),
    sample("2026-08-20T12:00:01.000Z", 0, 1_050, 1_050),
  ],
});

assert.equal(movingBacklog.result.canonicalCeilingStable, false);
assert.match(movingBacklog.failures.join("\n"), /canonical ceiling changed during drain/);

const redistributedCeiling = buildProjectionDrainReport({
  startedAt: "2026-08-20T12:00:00.000Z",
  finishedAt: "2026-08-20T12:00:01.000Z",
  projectorUrls: ["http://projector-0"],
  samples: [
    partitionedSample("2026-08-20T12:00:00.000Z", [500, 500], [450, 450]),
    partitionedSample("2026-08-20T12:00:01.000Z", [600, 400], [600, 400]),
  ],
});

assert.equal(redistributedCeiling.result.initialCanonicalCeiling, 1_000);
assert.equal(redistributedCeiling.result.finalCanonicalCeiling, 1_000);
assert.equal(redistributedCeiling.result.canonicalCeilingStable, false);

function sample(sampledAt, lag, canonicalCeiling, projectedWatermark) {
  return {
    sampledAt,
    status: {
      lag,
      metrics: { projected: projectedWatermark },
      watermarks: [
        {
          partition: 0,
          canonicalMaxPartitionSequence: canonicalCeiling,
          lastPartitionSequence: projectedWatermark,
          lag,
        },
      ],
    },
    probes: {
      status: [{ ok: true }],
      hotPath: [{ ok: true }],
      dbPools: [{ ok: true }],
    },
  };
}

function partitionedSample(sampledAt, canonicalCeilings, projectedWatermarks) {
  const watermarks = canonicalCeilings.map((canonicalMaxPartitionSequence, partition) => ({
    partition,
    canonicalMaxPartitionSequence,
    lastPartitionSequence: projectedWatermarks[partition],
    lag: canonicalMaxPartitionSequence - projectedWatermarks[partition],
  }));
  return {
    sampledAt,
    status: {
      lag: watermarks.reduce((sum, watermark) => sum + watermark.lag, 0),
      watermarks,
    },
    probes: {
      status: [{ ok: true }],
      hotPath: [{ ok: true }],
      dbPools: [{ ok: true }],
    },
  };
}
