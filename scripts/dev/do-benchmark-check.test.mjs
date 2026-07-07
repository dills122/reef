import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

const artifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-"));

writeReport("rate-2500.json", 2500, {
  totalRequests: 100,
  totalSuccess: 100,
  directAcked: 99,
  materialized: 98,
  projected: 97,
  lag: 3,
  p95: 12.5,
  p99: 25.1,
});
writeReport("rate-5000.json", 5000, {
  totalRequests: 200,
  totalSuccess: 200,
  directAcked: 198,
  materialized: 196,
  projected: 194,
  lag: 5,
  p95: 14.5,
  p99: 28.1,
});
writeFileSync(
  join(artifactDir, "sample-telemetry.ndjson"),
  `${JSON.stringify({
    app: {
      probes: [
        { name: "runtime.dbPools", ok: true },
        { name: "runtime.streamAckHealth", ok: true },
      ],
    },
  })}\n`,
);

const result = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", artifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REQUIRED_RATES: "2500,5000",
  },
  encoding: "utf8",
});

assert.equal(result.status, 0, result.stderr);
assert.match(result.stdout, /DO benchmark evidence summary:/);
assert.match(result.stdout, /rate=2500 attempted=100 accepted=100 directAcked=99 materialized=98 projected=97/);
assert.match(result.stdout, /DO benchmark report gates passed/);

const summary = JSON.parse(readFileSync(join(artifactDir, "do-benchmark-evidence-summary.json"), "utf8"));
assert.equal(summary.reports.length, 2);
assert.equal(summary.reports[0].evidence.gaps.acceptedToMaterialized, 2);
assert.equal(summary.reports[1].evidence.p99LatencyMs, 28.1);

function writeReport(name, rate, values) {
  writeFileSync(
    join(artifactDir, name),
    JSON.stringify(
      {
        config: { ratePerSecond: rate, workers: 256 },
        totalRequests: values.totalRequests,
        totalSuccess: values.totalSuccess,
        totalFailures: 0,
        statusCodes: { 202: values.totalSuccess },
        throughputRps: rate,
        acceptedBusinessOpsRps: rate,
        latencyMs: { p95: values.p95, p99: values.p99 },
        traceChecks: { checked: 0, pass: 0, fail: 0 },
        unitMetrics: {
          attemptedCommands: values.totalRequests,
          acceptedCommands: values.totalSuccess,
          directAckedCommands: values.directAcked,
          durableCanonicalCompletedItems: values.materialized,
          projectedWorkItems: values.projected,
          projectionLagAfter: values.lag,
          attemptedCommandsPerSecond: rate,
          acceptedCommandsPerSecond: rate,
          directAckedCommandsPerSecond: values.directAcked,
          durableCanonicalCompletedPerSecond: values.materialized,
          projectedWorkItemsPerSecond: values.projected,
        },
        streamAckWorkers: {
          delta: {
            completedDelta: values.totalSuccess,
            failedDelta: 0,
            ackFailedDelta: 0,
            unsupportedDelta: 0,
          },
        },
        streamAckProjector: {
          delta: {
            projectedDelta: values.projected,
            afterLag: values.lag,
          },
          after: {
            enabled: true,
            metrics: { failed: 0, lastError: "" },
            watermarks: [],
          },
        },
        streamAckApiPhases: { phases: [] },
      },
      null,
      2,
    ),
  );
}
