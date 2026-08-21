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
assert.match(result.stdout, /materializedProjectedGap=1 projectionFreshness=not-caught-up/);
assert.match(result.stdout, /DO benchmark report gates passed/);

const summary = JSON.parse(readFileSync(join(artifactDir, "do-benchmark-evidence-summary.json"), "utf8"));
assert.equal(summary.reports.length, 2);
assert.equal(summary.reports[0].evidence.gaps.acceptedToMaterialized, 2);
assert.equal(summary.reports[0].evidence.projectionFreshness.materializedToProjectedGap, 1);
assert.equal(summary.reports[0].evidence.projectionFreshness.caughtUp, false);
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

const duplicateArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-duplicate-"));
writeMaterializerReport(duplicateArtifactDir, "rate-10000.json", 10000, 1000, {
  published: 1001,
  directAcked: 1001,
  materialized: 1001,
});
writeTelemetry(duplicateArtifactDir);
const duplicateResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", duplicateArtifactDir], {
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
assert.equal(duplicateResult.status, 1);
assert.match(duplicateResult.stderr, /accepted\/published difference 1 must be 0/);
assert.match(duplicateResult.stderr, /accepted\/acked difference 1 must be 0/);
assert.match(duplicateResult.stderr, /accepted\/materialized difference 1 must be 0/);

const projectionArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-"));
writeMaterializerProjectionReport(projectionArtifactDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
});
writeTelemetry(projectionArtifactDir);
const projectionResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", projectionArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer-projection",
    REEF_DO_REQUIRED_RATES: "2500",
    REEF_DO_MIN_PROJECTED_RPS: "2400",
    REEF_DO_MAX_PROJECTION_LAG: "0",
    REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP: "0",
    REEF_DO_MAX_PROJECTION_DB_RETRIES: "0",
  },
  encoding: "utf8",
});
assert.equal(projectionResult.status, 0, projectionResult.stderr);
assert.match(projectionResult.stdout, /projected=2500 lag=0/);
assert.match(projectionResult.stdout, /materializedProjectedGap=0 projectionFreshness=caught-up/);

const projectionInvalidMaintainerConfigResult = spawnSync(
  process.execPath,
  ["scripts/dev/do-benchmark-check.mjs", projectionArtifactDir],
  {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_REPORT_PROFILE: "materializer-projection",
      REEF_DO_REQUIRED_RATES: "2500",
      REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS: "one",
    },
    encoding: "utf8",
  },
);
assert.equal(projectionInvalidMaintainerConfigResult.status, 1);
assert.match(
  projectionInvalidMaintainerConfigResult.stderr,
  /REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS must be a non-negative integer, got "one"/,
);

const projectionMaintainerTopologyDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-maintainers-"));
writeMaterializerProjectionReport(projectionMaintainerTopologyDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
  projectors: Array.from({ length: 4 }, (_, index) => ({
    index,
    orderLifecycleProjectorEnabled: true,
    marketDataProjectorEnabled: true,
  })),
});
writeTelemetry(projectionMaintainerTopologyDir);
const projectionMaintainerTopologyResult = spawnSync(
  process.execPath,
  ["scripts/dev/do-benchmark-check.mjs", projectionMaintainerTopologyDir],
  {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_REPORT_PROFILE: "materializer-projection",
      REEF_DO_REQUIRED_RATES: "2500",
      REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS: "1",
      REEF_DO_REQUIRED_MARKET_DATA_MAINTAINERS: "1",
    },
    encoding: "utf8",
  },
);
assert.equal(projectionMaintainerTopologyResult.status, 1);
assert.match(projectionMaintainerTopologyResult.stderr, /order-lifecycle maintainers 4 != required 1/);
assert.match(projectionMaintainerTopologyResult.stderr, /market-data maintainers 4 != required 1/);

const projectionSingleMaintainerDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-single-maintainer-"));
writeMaterializerProjectionReport(projectionSingleMaintainerDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
  projectors: Array.from({ length: 4 }, (_, index) => ({
    index,
    orderLifecycleProjectorEnabled: index === 0,
    marketDataProjectorEnabled: index === 0,
  })),
});
writeTelemetry(projectionSingleMaintainerDir);
const projectionSingleMaintainerResult = spawnSync(
  process.execPath,
  ["scripts/dev/do-benchmark-check.mjs", projectionSingleMaintainerDir],
  {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_REPORT_PROFILE: "materializer-projection",
      REEF_DO_REQUIRED_RATES: "2500",
      REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS: "1",
      REEF_DO_REQUIRED_MARKET_DATA_MAINTAINERS: "1",
    },
    encoding: "utf8",
  },
);
assert.equal(projectionSingleMaintainerResult.status, 0, projectionSingleMaintainerResult.stderr);

const projectionUnhealthyMaintainerDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-unhealthy-"));
writeMaterializerProjectionReport(projectionUnhealthyMaintainerDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
  orderLifecycleFailureDelta: 1,
  projectors: Array.from({ length: 4 }, (_, index) => ({
    index,
    orderLifecycleProjectorEnabled: index === 0,
    marketDataProjectorEnabled: index === 0,
  })),
});
writeTelemetry(projectionUnhealthyMaintainerDir);
const projectionUnhealthyMaintainerResult = spawnSync(
  process.execPath,
  ["scripts/dev/do-benchmark-check.mjs", projectionUnhealthyMaintainerDir],
  {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_REPORT_PROFILE: "materializer-projection",
      REEF_DO_REQUIRED_RATES: "2500",
      REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS: "1",
      REEF_DO_REQUIRED_MARKET_DATA_MAINTAINERS: "1",
    },
    encoding: "utf8",
  },
);
assert.equal(projectionUnhealthyMaintainerResult.status, 1);
assert.match(
  projectionUnhealthyMaintainerResult.stderr,
  /order-lifecycle maintainer index=0 failed delta must be 0, got 1/,
);

const projectionBackloggedMaintainerDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-backlogged-"));
writeMaterializerProjectionReport(projectionBackloggedMaintainerDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
  orderLifecycleLastProcessedRows: 25,
  projectors: Array.from({ length: 4 }, (_, index) => ({
    index,
    orderLifecycleProjectorEnabled: index === 0,
    marketDataProjectorEnabled: index === 0,
  })),
});
writeTelemetry(projectionBackloggedMaintainerDir);
const projectionBackloggedMaintainerResult = spawnSync(
  process.execPath,
  ["scripts/dev/do-benchmark-check.mjs", projectionBackloggedMaintainerDir],
  {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_REPORT_PROFILE: "materializer-projection",
      REEF_DO_REQUIRED_RATES: "2500",
      REEF_DO_REQUIRED_ORDER_LIFECYCLE_MAINTAINERS: "1",
      REEF_DO_REQUIRED_MARKET_DATA_MAINTAINERS: "1",
    },
    encoding: "utf8",
  },
);
assert.equal(projectionBackloggedMaintainerResult.status, 1);
assert.match(
  projectionBackloggedMaintainerResult.stderr,
  /order-lifecycle maintainer index=0 last processed rows must be 0, got 25/,
);

const projectionMissingPoolTelemetryDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-pools-"));
writeMaterializerProjectionReport(projectionMissingPoolTelemetryDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
});
writeTelemetry(projectionMissingPoolTelemetryDir, { projectorPools: false });
const projectionMissingPoolTelemetryResult = spawnSync(
  process.execPath,
  ["scripts/dev/do-benchmark-check.mjs", projectionMissingPoolTelemetryDir],
  {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_REPORT_PROFILE: "materializer-projection",
      REEF_DO_REQUIRED_RATES: "2500",
    },
    encoding: "utf8",
  },
);
assert.equal(projectionMissingPoolTelemetryResult.status, 1);
assert.match(projectionMissingPoolTelemetryResult.stderr, /projector DB-pool probes; missing indices 0,1,2,3/);

const projectionDiagnosticsArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-diagnostics-"));
writeMaterializerProjectionReport(projectionDiagnosticsArtifactDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
});
writeTelemetry(projectionDiagnosticsArtifactDir);
writeMaterializerDbDiagnostics(projectionDiagnosticsArtifactDir, { includeProjectionPostgres: true });
const projectionDiagnosticsResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", projectionDiagnosticsArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer-projection",
    REEF_DO_REQUIRED_RATES: "2500",
    REEF_DO_REQUIRE_DB_DIAGNOSTICS: "1",
    REEF_DO_REQUIRE_PG_STAT_IO: "1",
    REEF_DO_MAX_PROJECTION_DB_DEADLOCKS: "0",
  },
  encoding: "utf8",
});
assert.equal(projectionDiagnosticsResult.status, 0, projectionDiagnosticsResult.stderr);

const projectionMissingStatementsDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-statements-"));
writeMaterializerProjectionReport(projectionMissingStatementsDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
});
writeTelemetry(projectionMissingStatementsDir);
writeMaterializerDbDiagnostics(projectionMissingStatementsDir, { includeProjectionPostgres: true });
const projectionMissingStatementsResult = spawnSync(
  process.execPath,
  ["scripts/dev/do-benchmark-check.mjs", projectionMissingStatementsDir],
  {
    cwd: process.cwd(),
    env: {
      ...process.env,
      REEF_DO_REPORT_PROFILE: "materializer-projection",
      REEF_DO_REQUIRED_RATES: "2500",
      REEF_DO_REQUIRE_DB_DIAGNOSTICS: "1",
      REEF_DO_REQUIRE_PG_STAT_STATEMENTS: "1",
    },
    encoding: "utf8",
  },
);
assert.equal(projectionMissingStatementsResult.status, 1);
assert.match(projectionMissingStatementsResult.stderr, /missing DB diagnostics artifact: .*pre-pg_stat_statements\.csv/);
assert.match(projectionMissingStatementsResult.stderr, /missing DB diagnostics artifact: .*post-pg_stat_statements\.csv/);

const projectionDeadlockArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-deadlock-"));
writeMaterializerProjectionReport(projectionDeadlockArtifactDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
});
writeTelemetry(projectionDeadlockArtifactDir);
writeMaterializerDbDiagnostics(projectionDeadlockArtifactDir, {
  includeProjectionPostgres: true,
  projectionDeadlocks: 1,
  projectionLogs: "ERROR: deadlock detected\n",
});
const projectionDeadlockResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", projectionDeadlockArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer-projection",
    REEF_DO_REQUIRED_RATES: "2500",
    REEF_DO_REQUIRE_DB_DIAGNOSTICS: "1",
    REEF_DO_MAX_PROJECTION_DB_DEADLOCKS: "0",
  },
  encoding: "utf8",
});
assert.equal(projectionDeadlockResult.status, 1);
assert.match(projectionDeadlockResult.stderr, /projection-postgres deadlocks 1\.00 > required 0\.00/);
assert.match(projectionDeadlockResult.stderr, /projection-postgres logs contain deadlock detected/);

const projectionGapArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-gap-"));
writeMaterializerProjectionReport(projectionGapArtifactDir, "rate-2500.json", 2500, 2500, {
  projected: 2490,
  projectedRps: 2395,
  lag: 2,
});
writeTelemetry(projectionGapArtifactDir);
const projectionGapResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", projectionGapArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer-projection",
    REEF_DO_REQUIRED_RATES: "2500",
    REEF_DO_MIN_PROJECTED_RPS: "2400",
    REEF_DO_MAX_PROJECTION_LAG: "0",
    REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP: "0",
  },
  encoding: "utf8",
});
assert.equal(projectionGapResult.status, 1);
assert.match(projectionGapResult.stderr, /actual projected rps 2395\.00 < required 2400\.00/);
assert.match(projectionGapResult.stderr, /actual projection lag 2\.00 > required 0\.00/);
assert.match(projectionGapResult.stderr, /materialized\/projected gap 10\.00 > required 0\.00/);

const projectionRetryArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-projection-retry-"));
writeMaterializerProjectionReport(projectionRetryArtifactDir, "rate-2500.json", 2500, 2500, {
  projected: 2500,
  projectedRps: 2495,
  lag: 0,
  retryDelta: 1,
});
writeTelemetry(projectionRetryArtifactDir);
const projectionRetryResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", projectionRetryArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "materializer-projection",
    REEF_DO_REQUIRED_RATES: "2500",
    REEF_DO_MAX_PROJECTION_DB_RETRIES: "0",
  },
  encoding: "utf8",
});
assert.equal(projectionRetryResult.status, 1);
assert.match(projectionRetryResult.stderr, /streamAckProjector\.delta\.retryDelta 1\.00 > required 0\.00/);

const arenaArtifactDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-arena-"));
writeArenaReport(arenaArtifactDir, { healthStatus: "warn" });
const arenaResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", arenaArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "arena",
  },
  encoding: "utf8",
});
assert.equal(arenaResult.status, 0, arenaResult.stderr);
assert.match(arenaResult.stdout, /DO benchmark report gates passed/);

const arenaHealthGateResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", arenaArtifactDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "arena",
    REEF_DO_ARENA_REQUIRE_HEALTH_PASS: "1",
  },
  encoding: "utf8",
});
assert.equal(arenaHealthGateResult.status, 1);
assert.match(arenaHealthGateResult.stderr, /healthSummary\.status must be pass/);

const arenaLagGateDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-arena-lag-"));
writeArenaReport(arenaLagGateDir, { healthStatus: "pass", finalCompletionLagMs: 31000 });
const arenaLagGateResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", arenaLagGateDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "arena",
    REEF_DO_ARENA_MAX_FINAL_COMPLETION_LAG_MS: "30000",
  },
  encoding: "utf8",
});
assert.equal(arenaLagGateResult.status, 1);
assert.match(arenaLagGateResult.stderr, /finalCompletionLagMs 31000\.00 > required 30000\.00/);

const arenaMissingSummaryDir = mkdtempSync(join(tmpdir(), "reef-do-benchmark-check-arena-no-summary-"));
writeArenaReport(arenaMissingSummaryDir, { healthStatus: "pass", includeHardeningSummary: false });
const arenaMissingSummaryResult = spawnSync(process.execPath, ["scripts/dev/do-benchmark-check.mjs", arenaMissingSummaryDir], {
  cwd: process.cwd(),
  env: {
    ...process.env,
    REEF_DO_REPORT_PROFILE: "arena",
  },
  encoding: "utf8",
});
assert.equal(arenaMissingSummaryResult.status, 1);
assert.match(arenaMissingSummaryResult.stderr, /missing arena hardening summary/);

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

function writeArenaReport(dir, { healthStatus, includeHardeningSummary = true, finalCompletionLagMs = 50 }) {
  writeFileSync(
    join(dir, "arena-local-tick-run.json"),
    JSON.stringify({
      schemaVersion: "reef.arena.localTickRun.v0",
      runId: "arena-do-test",
      status: "completed",
      runPlan: { tickCount: 3 },
      totals: {
        ticks: 15,
        venueCommands: 14,
        submittedCommands: 14,
        completedCommands: 14,
        failedTicks: 0,
      },
      commandAccounting: {
        accountingGap: 0,
      },
      commandStatusSummary: {
        timedOut: 0,
      },
      healthSummary: {
        status: healthStatus,
        sampleCount: 15,
        topOfBookPct: 100,
        medianQuotedSpreadBps: 99.5,
        failures: healthStatus === "pass" ? [] : ["medianQuotedSpreadBps 99.50 > 25"],
      },
      pacingSummary: {
        schemaVersion: "reef.arena.pacingSummary.v0",
        enabled: true,
        finalCompletionLagMs,
      },
      botResults: [{ botId: "builtin-mm-simple", latencyP95Ms: 1 }],
      venueReadback: {
        availability: {
          body: {
            projections: [{ projectedCount: 14, lag: 0 }],
          },
        },
      },
    }),
  );
  writeFileSync(
    join(dir, "arena-export.json"),
    JSON.stringify({
      runId: "arena-do-test",
      runKind: "arena-do",
      status: "completed",
    }),
  );
  if (includeHardeningSummary) {
    writeFileSync(
      join(dir, "arena-local-tick-run.summary.json"),
      JSON.stringify({
        schemaVersion: "reef.arena.localHardeningSummary.v0",
        status: "pass",
        failures: [],
      }),
    );
  }
}

function writeTelemetry(dir, { projectorPools = true } = {}) {
  const projectorPoolProbes = projectorPools
    ? Array.from({ length: 4 }, (_, index) => ({ name: `streamAckProjector.${index}.dbPools`, ok: true }))
    : [];
  writeFileSync(
    join(dir, "sample-telemetry.ndjson"),
    `${JSON.stringify({
      app: {
        probes: [
          { name: "runtime.dbPools", ok: true },
          { name: "runtime.streamAckHealth", ok: true },
          ...projectorPoolProbes,
        ],
      },
    })}\n`,
  );
}

function writeMaterializerReport(dir, name, rate, total, options = {}) {
  const directAcked = options.directAcked ?? total;
  const materialized = options.materialized ?? total;
  const published = options.published ?? total;
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
          directAckedCommands: directAcked,
          durableCanonicalCompletedItems: materialized,
          attemptedCommandsPerSecond: rate,
          acceptedCommandsPerSecond: rate,
          directAckedCommandsPerSecond: directAcked,
          durableCanonicalCompletedPerSecond: materialized,
        },
        streamDirect: {
          delta: {
            publishedDelta: published,
            ackedDelta: directAcked,
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
            materializedDelta: materialized,
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

function writeMaterializerProjectionReport(dir, name, rate, total, options) {
  const projectors = options.projectors ?? [];
  const downstreamStatus = (enabled, cycles, failed = 0, lastProcessedRows = 0) => ({
    enabled,
    metrics: {
      cycles,
      processedRows: enabled ? total : 0,
      lastProcessedRows,
      failed,
      lastProcessedAt: enabled ? "2026-08-20T12:00:00Z" : "",
      lastFailedAt: failed > 0 ? "2026-08-20T12:00:01Z" : "",
      lastError: failed > 0 ? "projection failed" : "",
    },
  });
  const beforeProjectors = projectors.map((projector) => ({
    ...projector,
    orderLifecycleProjector: downstreamStatus(projector.orderLifecycleProjectorEnabled === true, 1),
    marketDataProjector: downstreamStatus(projector.marketDataProjectorEnabled === true, 1),
  }));
  const afterProjectors = projectors.map((projector) => ({
    ...projector,
    orderLifecycleProjector: downstreamStatus(
      projector.orderLifecycleProjectorEnabled === true,
      projector.orderLifecycleProjectorEnabled === true ? 2 : 1,
      projector.orderLifecycleProjectorEnabled === true ? options.orderLifecycleFailureDelta ?? 0 : 0,
      projector.orderLifecycleProjectorEnabled === true ? options.orderLifecycleLastProcessedRows ?? 0 : 0,
    ),
    marketDataProjector: downstreamStatus(
      projector.marketDataProjectorEnabled === true,
      projector.marketDataProjectorEnabled === true ? 2 : 1,
      projector.marketDataProjectorEnabled === true ? options.marketDataFailureDelta ?? 0 : 0,
      projector.marketDataProjectorEnabled === true ? options.marketDataLastProcessedRows ?? 0 : 0,
    ),
  }));
  writeFileSync(
    join(dir, name),
    JSON.stringify(
      {
        config: { ratePerSecond: rate, workers: 256 },
        totalRequests: total,
        totalSuccess: total,
        totalFailures: 0,
        statusCodes: { 202: total },
        throughputRps: rate,
        acceptedBusinessOpsRps: rate,
        latencyMs: { p95: 38.4, p99: 70.2 },
        traceChecks: { checked: 0, pass: 0, fail: 0 },
        unitMetrics: {
          attemptedCommands: total,
          acceptedCommands: total,
          directAckedCommands: total,
          durableCanonicalCompletedItems: total,
          projectedWorkItems: options.projected,
          projectionLagAfter: options.lag,
          attemptedCommandsPerSecond: rate,
          acceptedCommandsPerSecond: rate,
          directAckedCommandsPerSecond: rate,
          durableCanonicalCompletedPerSecond: rate,
          projectedWorkItemsPerSecond: options.projectedRps,
        },
        streamDirect: {
          delta: {
            publishedDelta: total,
            ackedDelta: total,
            failedDelta: 0,
            nackedDelta: 0,
            termedDelta: 0,
            unsupportedDelta: 0,
            partitionDeltas: [
              { partition: 0, ackedDelta: total / 4 },
              { partition: 1, ackedDelta: total / 4 },
              { partition: 2, ackedDelta: total / 4 },
              { partition: 3, ackedDelta: total / 4 },
            ],
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
        streamAckProjector: {
          before: {
            enabled: true,
            projectors: beforeProjectors,
            metrics: { failed: 0, lastError: "" },
            watermarks: [],
          },
          delta: {
            projectedDelta: options.projected,
            failedDelta: 0,
            retryDelta: options.retryDelta ?? 0,
            retryExhaustedDelta: options.retryExhaustedDelta ?? 0,
            afterLag: options.lag,
          },
          after: {
            enabled: true,
            projectors: afterProjectors,
            metrics: {
              failed: 0,
              lastError: "",
              retryAttempts: options.retryDelta ?? 0,
              retryExhausted: options.retryExhaustedDelta ?? 0,
              lastRetryAt: "",
              lastRetrySqlState: "",
              lastRetryError: "",
            },
            watermarks: [],
          },
          probes: { after: { ok: true, status: 200 } },
        },
      },
      null,
      2,
    ),
  );
}

function writeMaterializerDbDiagnostics(dir, options = {}) {
  const services = {
    postgres: dbDiagnosticsServiceSummary({ walBytes: 2048 }),
  };
  if (options.includeProjectionPostgres) {
    services["projection-postgres"] = dbDiagnosticsServiceSummary({
      walBytes: 1024,
      deadlocks: options.projectionDeadlocks ?? 0,
    });
  }
  writeFileSync(
    join(dir, "venue-event-materializer-stress-diagnostics-summary.json"),
    JSON.stringify(
      {
        services,
      },
      null,
      2,
    ),
  );
  const diagnosticsDir = join(dir, "venue-event-materializer-stress-diagnostics");
  mkdirSync(diagnosticsDir);
  writeDbDiagnosticsFiles(diagnosticsDir);
  if (options.includeProjectionPostgres) {
    const postgresDir = join(diagnosticsDir, "postgres");
    const projectionDir = join(diagnosticsDir, "projection-postgres");
    mkdirSync(postgresDir);
    mkdirSync(projectionDir);
    writeDbDiagnosticsFiles(postgresDir);
    writeDbDiagnosticsFiles(projectionDir);
    writeFileSync(join(projectionDir, "postgres-logs.txt"), options.projectionLogs ?? "");
  }
}

function dbDiagnosticsServiceSummary({ walBytes, deadlocks = 0 }) {
  return {
    ok: true,
    unitMetrics: {
      walBytes,
      walBytesPerAcceptedCommand: walBytes / 1000,
    },
    wal: {
      walBytes,
    },
    database: {
      deadlocks,
    },
    topTablesByBytes: [{ table: "runtime.canonical_command_outcomes", totalBytesDelta: 1024 }],
  };
}

function writeDbDiagnosticsFiles(diagnosticsDir) {
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
