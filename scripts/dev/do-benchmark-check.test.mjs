import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
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

const materializerArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-materializer-"));
writeMaterializerReport(materializerArtifactDir, "rate-10000.json", 10000, 1000);
writeTelemetry(materializerArtifactDir);
const materializerResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", materializerArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer",
    REEF_DO_REQUIRED_RATES: "10000",
    REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS: "4",
    REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW: "1.1",
  },
  encoding: "utf8",
});
assert.equal(materializerResult.status, 0, materializerResult.stderr);
assert.match(materializerResult.stdout, /rate=10000 attempted=1000 accepted=1000 directAcked=1000 materialized=1000/);

const blockedArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-blocked-"));
writeBlockedStreamAckReport(blockedArtifactDir, "rate-2500.json", 2500);
writeTelemetry(blockedArtifactDir);
const blockedResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", blockedArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "stream-ack",
    REEF_DO_REQUIRED_RATES: "2500",
  },
  encoding: "utf8",
});
assert.equal(blockedResult.status, 1);
assert.match(blockedResult.stderr, /internal diagnostics were blocked by PLATFORM_INTERNAL_HTTP_MODE/);

const latencyGateArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-latency-"));
writeMaterializerReport(latencyGateArtifactDir, "rate-10000.json", 10000, 1000);
writeTelemetry(latencyGateArtifactDir);
const latencyGateResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", latencyGateArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer",
    REEF_DO_REQUIRED_RATES: "10000",
    REEF_DO_MAX_P95_MS: "40",
  },
  encoding: "utf8",
});
assert.equal(latencyGateResult.status, 1);
assert.match(latencyGateResult.stderr, /actual p95 44\.90ms > required 40\.00ms/);

const partitionGateArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-partitions-"));
writeMaterializerReport(partitionGateArtifactDir, "rate-10000.json", 10000, 1000, {
  partitionDeltas: [
    { partition: 0, ackedDelta: 900 },
    { partition: 1, ackedDelta: 100 },
    { partition: 2, ackedDelta: 0 },
    { partition: 3, ackedDelta: 0 },
  ],
});
writeTelemetry(partitionGateArtifactDir);
const partitionGateResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", partitionGateArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer",
    REEF_DO_REQUIRED_RATES: "10000",
    REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS: "3",
    REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW: "4",
  },
  encoding: "utf8",
});
assert.equal(partitionGateResult.status, 1);
assert.match(partitionGateResult.stderr, /streamDirect active partitions 2 < required 3\.00/);
assert.match(partitionGateResult.stderr, /streamDirect partition skew 9\.00 > required 4\.00/);

const diagnosticsGateArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-diagnostics-"));
writeMaterializerReport(diagnosticsGateArtifactDir, "rate-10000.json", 10000, 1000);
writeTelemetry(diagnosticsGateArtifactDir);
writeMaterializerDbDiagnostics(diagnosticsGateArtifactDir);
const diagnosticsGateResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", diagnosticsGateArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer",
    REEF_DO_REQUIRED_RATES: "10000",
    REEF_DO_REQUIRE_DB_DIAGNOSTICS: "1",
    REEF_DO_REQUIRE_PG_STAT_IO: "1",
  },
  encoding: "utf8",
});
assert.equal(diagnosticsGateResult.status, 0, diagnosticsGateResult.stderr);

const missingDiagnosticsArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-missing-diagnostics-"));
writeMaterializerReport(missingDiagnosticsArtifactDir, "rate-10000.json", 10000, 1000);
writeTelemetry(missingDiagnosticsArtifactDir);
const missingDiagnosticsResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", missingDiagnosticsArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer",
    REEF_DO_REQUIRED_RATES: "10000",
    REEF_DO_REQUIRE_DB_DIAGNOSTICS: "1",
  },
  encoding: "utf8",
});
assert.equal(missingDiagnosticsResult.status, 1);
assert.match(missingDiagnosticsResult.stderr, /missing materializer DB diagnostics summary/);

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

function writeTelemetry(dir) {
  writeFileSync(
    join(dir, "sample-telemetry.ndjson"),
    `${JSON.stringify({
      app: {
        probes: [
          { name: "runtime.dbPools", ok: true },
          { name: "runtime.streamAckHealth", ok: true },
        ],
      },
    })}\n`,
  );
}

function writeMaterializerReport(dir, name, rate, total, options = {}) {
  const partitionDeltas =
    options.partitionDeltas ??
    [
      { partition: 0, ackedDelta: total / 4 },
      { partition: 1, ackedDelta: total / 4 },
      { partition: 2, ackedDelta: total / 4 },
      { partition: 3, ackedDelta: total / 4 },
    ];
  writeFileSync(
    join(dir, name),
    JSON.stringify(
      {
        config: { ratePerSecond: rate, workers: 384 },
        totalRequests: total,
        totalSuccess: total,
        totalFailures: 0,
        statusCodes: { 202: total },
        throughputRps: rate,
        acceptedBusinessOpsRps: rate,
        latencyMs: { p95: 44.9, p99: 68.6 },
        traceChecks: { checked: 0, pass: 0, fail: 0 },
        unitMetrics: {
          attemptedCommands: total,
          acceptedCommands: total,
          directAckedCommands: total,
          durableCanonicalCompletedItems: total,
          attemptedCommandsPerSecond: rate,
          acceptedCommandsPerSecond: rate,
          directAckedCommandsPerSecond: rate,
          durableCanonicalCompletedPerSecond: rate,
        },
        streamDirect: {
          delta: {
            ackedDelta: total,
            failedDelta: 0,
            nackedDelta: 0,
            termedDelta: 0,
            unsupportedDelta: 0,
            partitionDeltas,
          },
          probes: { after: { ok: true, status: 200 } },
        },
        venueEventMaterializer: {
          delta: {
            materializedDelta: total,
            failedDelta: 0,
            ackFailedDelta: 0,
            unsupportedDelta: 0,
          },
          probes: { after: { ok: true, status: 200 } },
        },
      },
      null,
      2,
    ),
  );
}

function writeMaterializerDbDiagnostics(dir) {
  writeFileSync(
    join(dir, "venue-event-materializer-stress-diagnostics-summary.json"),
    JSON.stringify(
      {
        services: {
          postgres: {
            ok: true,
            unitMetrics: {
              walBytes: 2048,
              walBytesPerAcceptedCommand: 2.048,
            },
            wal: {
              walBytes: 2048,
            },
            topTablesByBytes: [{ table: "runtime.canonical_command_outcomes", totalBytesDelta: 1024 }],
          },
        },
      },
      null,
      2,
    ),
  );
  const diagnosticsDir = join(dir, "venue-event-materializer-stress-diagnostics");
  mkdirSync(diagnosticsDir);
  for (const file of [
    "pre-db-diagnostics.json",
    "post-db-diagnostics.json",
    "pre-pg_stat_wal.csv",
    "post-pg_stat_wal.csv",
    "pre-pg_stat_database.csv",
    "post-pg_stat_database.csv",
    "pre-pg_stat_activity_waits.csv",
    "post-pg_stat_activity_waits.csv",
    "pre-pg_settings_wal.csv",
    "post-pg_settings_wal.csv",
    "pre-table-stats.csv",
    "post-table-stats.csv",
    "pre-pg_stat_io.csv",
    "post-pg_stat_io.csv",
  ]) {
    writeFileSync(join(diagnosticsDir, file), file.endsWith(".json") ? "{}\n" : "name,value\n");
  }
}

function writeBlockedStreamAckReport(dir, name, rate) {
  writeFileSync(
    join(dir, name),
    JSON.stringify(
      {
        config: { ratePerSecond: rate, workers: 256 },
        totalRequests: 100,
        totalSuccess: 100,
        totalFailures: 0,
        statusCodes: { 202: 100 },
        throughputRps: rate,
        acceptedBusinessOpsRps: rate,
        latencyMs: { p95: 10, p99: 20 },
        traceChecks: { checked: 0, pass: 0, fail: 0 },
        streamAckWorkers: {
          probes: {
            after: {
              ok: false,
              status: 403,
              json: { error: "internal HTTP route requires loopback access", mode: "local" },
            },
          },
        },
      },
      null,
      2,
    ),
  );
}
