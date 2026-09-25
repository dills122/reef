import assert from "node:assert/strict";
import test from "node:test";
import { summarizeDownstreamDiagnosticSamples } from "./downstream-sampler.mjs";

test("summarizes successful one-second downstream diagnostic coverage", () => {
  const summary = summarizeDownstreamDiagnosticSamples({
    samples: [sample("2026-08-24T18:00:00Z"), sample("2026-08-24T18:00:01Z"), sample("2026-08-24T18:00:02Z")],
    startAt: "2026-08-24T18:00:00Z",
    endAt: "2026-08-24T18:00:02Z",
    configuredIntervalMs: 1000,
    projectorIndices: [0],
  });

  assert.deepEqual(summary, {
    configuredIntervalMs: 1000,
    maxAllowedGapMs: 2000,
    sampleCount: 3,
    successfulSampleCount: 3,
    maxGapMs: 1000,
    startedAt: "2026-08-24T18:00:00Z",
    finishedAt: "2026-08-24T18:00:02Z",
    projectorIndices: [0],
  });
});

test("counts failed probes and uncovered tail time", () => {
  const failed = sample("2026-08-24T18:00:01Z");
  failed.app.probes[1].ok = false;
  const summary = summarizeDownstreamDiagnosticSamples({
    samples: [sample("2026-08-24T18:00:00Z"), failed],
    startAt: "2026-08-24T18:00:00Z",
    endAt: "2026-08-24T18:00:04Z",
    configuredIntervalMs: 1000,
    projectorIndices: [0],
  });

  assert.equal(summary.sampleCount, 2);
  assert.equal(summary.successfulSampleCount, 1);
  assert.equal(summary.maxGapMs, 3000);
});

function sample(sampledAt) {
  return {
    sampledAt,
    app: {
      probes: [
        probe("streamAckProjector.0.orderLifecycleStatus"),
        probe("streamAckProjector.0.marketDataStatus"),
      ],
    },
  };
}

function probe(name) {
  return { name, ok: true, json: { instrumentation: { enabled: true } } };
}
