import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { canonicalEvidenceSummary } from "./lib/report-taxonomy.mjs";

const target = process.argv[2];
if (!target) {
  console.error("usage: node scripts/dev/do-benchmark-check.mjs <artifact-dir>");
  process.exit(2);
}

const requiredRates = parseCsvInts(process.env.REEF_DO_REQUIRED_RATES || "2500,5000");
const allowBackpressure429 = process.env.REEF_DO_ALLOW_429 === "1";
const requireTraceChecks = process.env.REEF_DO_REQUIRE_TRACE_CHECKS === "1";
const reportProfile = process.env.REEF_DO_REPORT_PROFILE || process.env.REEF_DO_BENCHMARK_PROFILE || "stream-ack";
const minAttemptedRps = parseOptionalNumber(process.env.REEF_DO_MIN_ATTEMPTED_RPS);
const minAcceptedRps = parseOptionalNumber(process.env.REEF_DO_MIN_ACCEPTED_RPS);
const minWorkerCompletedRps = parseOptionalNumber(process.env.REEF_DO_MIN_WORKER_COMPLETED_RPS);
const maxP95Ms = parseOptionalNumber(process.env.REEF_DO_MAX_P95_MS);
const maxP99Ms = parseOptionalNumber(process.env.REEF_DO_MAX_P99_MS);
const minProjectedRps = parseOptionalNumber(process.env.REEF_DO_MIN_PROJECTED_RPS);
const maxProjectionLag = parseOptionalNonNegativeNumber(process.env.REEF_DO_MAX_PROJECTION_LAG);
const maxMaterializedToProjectedGap = parseOptionalNonNegativeNumber(process.env.REEF_DO_MAX_MATERIALIZED_TO_PROJECTED_GAP);
const minStreamDirectActivePartitions = parseOptionalNumber(process.env.REEF_DO_MIN_STREAM_DIRECT_ACTIVE_PARTITIONS);
const maxStreamDirectPartitionSkew = parseOptionalNumber(process.env.REEF_DO_MAX_STREAM_DIRECT_PARTITION_SKEW);
const requireDbDiagnostics = process.env.REEF_DO_REQUIRE_DB_DIAGNOSTICS === "1";
const requirePgStatIo = process.env.REEF_DO_REQUIRE_PG_STAT_IO === "1";
const failures = [];
const evidenceRows = [];

if (!existsSync(target)) {
  failures.push(`artifact directory does not exist: ${target}`);
  finish();
}

const jsonFiles = walk(target).filter((path) => path.endsWith(".json"));
const reports = jsonFiles
  .map((path) => readJson(path))
  .filter((entry) => entry.ok && isMeasuredStressReport(entry.json))
  .map((entry) => ({ path: entry.path, report: entry.json }));

for (const entry of jsonFiles.map((path) => readJson(path)).filter((entry) => !entry.ok)) {
  failures.push(`invalid JSON: ${entry.path}: ${entry.error}`);
}

if (reportProfile === "arena") {
  validateArenaArtifacts(jsonFiles);
  finish();
  process.exit(0);
}

for (const rate of requiredRates) {
  const matches = reports.filter(({ report }) => reportRatePerSecond(report) === rate);
  if (matches.length === 0) {
    failures.push(`missing measured stress report for rate ${rate}`);
    continue;
  }
  for (const match of matches) {
    validateReport(match.path, match.report);
  }
}

validateTelemetry(target);
if ((reportProfile === "materializer" || reportProfile === "materializer-projection") && requireDbDiagnostics) {
  validateMaterializerDbDiagnostics(target);
}

finish();

function validateReport(path, report) {
  const label = `${path} rate=${reportRatePerSecond(report) ?? "unknown"}`;
  evidenceRows.push({
    path,
    rate: reportRatePerSecond(report) ?? 0,
    evidence: canonicalEvidenceSummary(report),
  });
  const total = Number(report.totalRequests ?? 0);
  const success = Number(report.totalSuccess ?? 0);
  const failuresCount = Number(report.totalFailures ?? 0);
  const attemptedRps = reportMetric(report, "attemptedCommandsPerSecond", "throughputRps");
  const acceptedRps = reportMetric(report, "acceptedCommandsPerSecond", "acceptedBusinessOpsRps");
  const workerCompletedRps = reportMetric(report, "workerCompletedCommandsPerSecond");
  const p95Ms = latencyMetric(report, "p95");
  const p99Ms = latencyMetric(report, "p99");
  if (total <= 0) {
    failures.push(`${label}: totalRequests must be > 0`);
  }
  if (minAttemptedRps !== undefined && attemptedRps < minAttemptedRps) {
    failures.push(`${label}: actual attempted rps ${formatNumber(attemptedRps)} < required ${formatNumber(minAttemptedRps)}`);
  }
  if (minAcceptedRps !== undefined && acceptedRps < minAcceptedRps) {
    failures.push(`${label}: actual accepted rps ${formatNumber(acceptedRps)} < required ${formatNumber(minAcceptedRps)}`);
  }
  if (minWorkerCompletedRps !== undefined && workerCompletedRps < minWorkerCompletedRps) {
    failures.push(
      `${label}: actual worker-completed rps ${formatNumber(workerCompletedRps)} < required ${formatNumber(minWorkerCompletedRps)}`,
    );
  }
  if (maxP95Ms !== undefined && p95Ms > maxP95Ms) {
    failures.push(`${label}: actual p95 ${formatNumber(p95Ms)}ms > required ${formatNumber(maxP95Ms)}ms`);
  }
  if (maxP99Ms !== undefined && p99Ms > maxP99Ms) {
    failures.push(`${label}: actual p99 ${formatNumber(p99Ms)}ms > required ${formatNumber(maxP99Ms)}ms`);
  }

  const statusCodes = normalizeStatusCodes(report.statusCodes ?? {});
  const unexpected5xx = [...statusCodes.entries()]
    .filter(([code]) => code >= 500 && code <= 599)
    .reduce((sum, [, count]) => sum + count, 0);
  if (unexpected5xx !== 0) {
    failures.push(`${label}: unexpected 5xx responses ${unexpected5xx}`);
  }

  const backpressure429 = statusCodes.get(429) ?? 0;
  const allowedFailures = allowBackpressure429 ? backpressure429 : 0;
  if (failuresCount > allowedFailures || success + allowedFailures !== total) {
    failures.push(
      `${label}: success gate failed total=${total} success=${success} failures=${failuresCount} allowed429=${allowedFailures}`,
    );
  }

  const traceChecked = Number(report.traceChecks?.checked ?? 0);
  const tracePass = Number(report.traceChecks?.pass ?? 0);
  if (requireTraceChecks && traceChecked > 0 && tracePass !== traceChecked) {
    failures.push(`${label}: trace checks failed pass=${tracePass} checked=${traceChecked}`);
  }

  if (reportProfile === "stream-ack") {
    validateStreamAckReport(label, report);
  } else if (reportProfile === "materializer" || reportProfile === "materializer-projection") {
    validateMaterializerReport(label, report);
    if (reportProfile === "materializer-projection") {
      validateMaterializerProjectionReport(label, report);
    }
  } else {
    failures.push(`${label}: unsupported REEF_DO_REPORT_PROFILE=${reportProfile}`);
  }
}

function validateStreamAckReport(label, report) {
  if (hasBlockedInternalProbe(report.streamAckWorkers?.probes) || hasBlockedInternalProbe(report.streamAckProjector?.probes)) {
    failures.push(`${label}: internal diagnostics were blocked by PLATFORM_INTERNAL_HTTP_MODE; set PLATFORM_INTERNAL_HTTP_MODE=enabled before starting the stack`);
    return;
  }

  const workerDelta = report.streamAckWorkers?.delta;
  if (!workerDelta) {
    failures.push(`${label}: missing streamAckWorkers.delta`);
  } else {
    checkZero(label, "streamAckWorkers.delta.failedDelta", workerDelta.failedDelta);
    checkZero(label, "streamAckWorkers.delta.ackFailedDelta", workerDelta.ackFailedDelta);
    checkZero(label, "streamAckWorkers.delta.unsupportedDelta", workerDelta.unsupportedDelta);
    if (Number(workerDelta.completedDelta ?? 0) <= 0) {
      failures.push(`${label}: streamAckWorkers.delta.completedDelta must be > 0`);
    }
  }

  const projectorDelta = report.streamAckProjector?.delta;
  if (!projectorDelta) {
    failures.push(`${label}: missing streamAckProjector.delta`);
  } else {
    if (!Number.isFinite(Number(projectorDelta.afterLag))) {
      failures.push(`${label}: streamAckProjector.delta.afterLag must be numeric`);
    }
    checkProjectorHealth(label, report.streamAckProjector?.after);
  }

  if (!report.streamAckApiPhases?.phases) {
    failures.push(`${label}: missing streamAckApiPhases.phases`);
  }
}

function validateMaterializerReport(label, report) {
  if (hasBlockedInternalProbe(report.streamDirect?.probes) || hasBlockedInternalProbe(report.venueEventMaterializer?.probes)) {
    failures.push(`${label}: internal diagnostics were blocked by PLATFORM_INTERNAL_HTTP_MODE; set PLATFORM_INTERNAL_HTTP_MODE=enabled before starting the stack`);
    return;
  }

  const directDelta = report.streamDirect?.delta;
  if (!directDelta) {
    failures.push(`${label}: missing streamDirect.delta`);
  } else {
    checkZero(label, "streamDirect.delta.failedDelta", directDelta.failedDelta);
    checkZero(label, "streamDirect.delta.nackedDelta", directDelta.nackedDelta);
    checkZero(label, "streamDirect.delta.termedDelta", directDelta.termedDelta);
    checkZero(label, "streamDirect.delta.unsupportedDelta", directDelta.unsupportedDelta);
    const accepted = Number(report.totalSuccess ?? 0);
    const acked = Number(directDelta.ackedDelta ?? 0);
    if (acked < accepted) {
      failures.push(`${label}: streamDirect accepted/acked gap ${accepted - acked} must be 0`);
    }
    validateStreamDirectPartitionSpread(label, directDelta);
  }

  const materializerDelta = report.venueEventMaterializer?.delta;
  if (!materializerDelta) {
    failures.push(`${label}: missing venueEventMaterializer.delta`);
  } else {
    checkZero(label, "venueEventMaterializer.delta.failedDelta", materializerDelta.failedDelta);
    checkZero(label, "venueEventMaterializer.delta.ackFailedDelta", materializerDelta.ackFailedDelta);
    checkZero(label, "venueEventMaterializer.delta.unsupportedDelta", materializerDelta.unsupportedDelta);
    const accepted = Number(report.totalSuccess ?? 0);
    const materialized = Number(materializerDelta.materializedDelta ?? 0);
    if (materialized < accepted) {
      failures.push(`${label}: durable-canonical accepted/materialized gap ${accepted - materialized} must be 0`);
    }
  }
}

function validateMaterializerProjectionReport(label, report) {
  if (hasBlockedInternalProbe(report.streamAckProjector?.probes)) {
    failures.push(`${label}: internal diagnostics were blocked by PLATFORM_INTERNAL_HTTP_MODE; set PLATFORM_INTERNAL_HTTP_MODE=enabled before starting the stack`);
    return;
  }

  const projectorDelta = report.streamAckProjector?.delta;
  if (!projectorDelta) {
    failures.push(`${label}: missing streamAckProjector.delta`);
    return;
  }

  checkZero(label, "streamAckProjector.delta.failedDelta", projectorDelta.failedDelta);
  const projected = Number(projectorDelta.projectedDelta ?? 0);
  if (projected <= 0) {
    failures.push(`${label}: streamAckProjector.delta.projectedDelta must be > 0`);
  }
  const projectedRps = reportMetric(report, "projectedWorkItemsPerSecond");
  if (minProjectedRps !== undefined && projectedRps < minProjectedRps) {
    failures.push(`${label}: actual projected rps ${formatNumber(projectedRps)} < required ${formatNumber(minProjectedRps)}`);
  }
  const afterLag = Number(projectorDelta.afterLag ?? report.unitMetrics?.projectionLagAfter);
  if (!Number.isFinite(afterLag)) {
    failures.push(`${label}: streamAckProjector.delta.afterLag must be numeric`);
  } else if (maxProjectionLag !== undefined && afterLag > maxProjectionLag) {
    failures.push(`${label}: actual projection lag ${formatNumber(afterLag)} > required ${formatNumber(maxProjectionLag)}`);
  }
  const materialized = Number(report.venueEventMaterializer?.delta?.materializedDelta ?? 0);
  const materializedToProjectedGap = Math.max(materialized - projected, 0);
  if (maxMaterializedToProjectedGap !== undefined && materializedToProjectedGap > maxMaterializedToProjectedGap) {
    failures.push(
      `${label}: materialized/projected gap ${formatNumber(materializedToProjectedGap)} > required ${formatNumber(maxMaterializedToProjectedGap)}`,
    );
  }
  checkProjectorHealth(label, report.streamAckProjector?.after);
}

function validateArenaArtifacts(jsonFiles) {
  const arenaReports = jsonFiles
    .map((path) => readJson(path))
    .filter((entry) => entry.ok && entry.json?.schemaVersion === "reef.arena.localTickRun.v0")
    .map((entry) => ({ path: entry.path, report: entry.json }));
  const exports = jsonFiles
    .map((path) => readJson(path))
    .filter((entry) => entry.ok && entry.json?.runKind === "arena-do")
    .map((entry) => ({ path: entry.path, report: entry.json }));

  if (arenaReports.length === 0) {
    failures.push("missing arena local tick report");
    return;
  }
  if (exports.length === 0) {
    failures.push("missing arena simulation export");
  }

  for (const { path, report } of arenaReports) {
    const label = `${path} run=${report.runId ?? "unknown"}`;
    const totals = report.totals ?? {};
    const accounting = report.commandAccounting ?? {};
    const commandStatus = report.commandStatusSummary ?? {};
    const health = report.healthSummary ?? {};
    const runPlan = report.runPlan ?? {};
    evidenceRows.push({
      path,
      rate: 0,
      evidence: arenaEvidenceSummary(report),
    });

    if (report.status !== "completed" && report.status !== "completed_with_freezes") {
      failures.push(`${label}: status must be completed or completed_with_freezes, got ${report.status}`);
    }
    if (Number(runPlan.tickCount ?? 0) <= 0) {
      failures.push(`${label}: runPlan.tickCount must be > 0`);
    }
    if (Number(totals.ticks ?? 0) <= 0) {
      failures.push(`${label}: totals.ticks must be > 0`);
    }
    if (Number(totals.venueCommands ?? 0) <= 0) {
      failures.push(`${label}: totals.venueCommands must be > 0`);
    }
    if (Number(accounting.accountingGap ?? 0) !== 0) {
      failures.push(`${label}: command accounting gap must be 0, got ${accounting.accountingGap}`);
    }
    if (Number(commandStatus.timedOut ?? 0) !== 0) {
      failures.push(`${label}: command status timeouts must be 0, got ${commandStatus.timedOut}`);
    }
    if (Number(totals.failedTicks ?? 0) !== 0) {
      failures.push(`${label}: failedTicks must be 0, got ${totals.failedTicks}`);
    }
    if (Number(health.sampleCount ?? 0) <= 0) {
      failures.push(`${label}: healthSummary.sampleCount must be > 0`);
    }
    if (process.env.REEF_DO_ARENA_REQUIRE_HEALTH_PASS === "1" && health.status !== "pass") {
      failures.push(`${label}: healthSummary.status must be pass, got ${health.status}: ${(health.failures ?? []).join("; ")}`);
    }
  }
}

function arenaEvidenceSummary(report) {
  const totals = report.totals ?? {};
  const health = report.healthSummary ?? {};
  return {
    attempted: Number(totals.venueCommands ?? 0),
    accepted: Number(totals.submittedCommands ?? 0),
    directAcked: Number(totals.completedCommands ?? 0),
    materialized: Number(report.venueReadback?.availability?.body?.projections?.[0]?.projectedCount ?? 0),
    projected: Number(report.venueReadback?.availability?.body?.projections?.[0]?.projectedCount ?? 0),
    lag: Number(report.venueReadback?.availability?.body?.projections?.[0]?.lag ?? 0),
    p95LatencyMs: Number(report.botResults?.reduce((max, bot) => Math.max(max, Number(bot.latencyP95Ms ?? 0)), 0) ?? 0),
    p99LatencyMs: 0,
    healthStatus: health.status ?? "unknown",
    topOfBookPct: Number(health.topOfBookPct ?? 0),
    medianQuotedSpreadBps: Number(health.medianQuotedSpreadBps ?? 0),
    gaps: {
      acceptedToMaterialized: Number(totals.submittedCommands ?? 0) - Number(report.venueReadback?.availability?.body?.projections?.[0]?.projectedCount ?? 0),
    },
  };
}

function validateStreamDirectPartitionSpread(label, directDelta) {
  if (minStreamDirectActivePartitions === undefined && maxStreamDirectPartitionSkew === undefined) return;

  const partitions = Array.isArray(directDelta.partitionDeltas) ? directDelta.partitionDeltas : [];
  if (partitions.length === 0) {
    failures.push(`${label}: streamDirect.delta.partitionDeltas missing; cannot validate partition spread gates`);
    return;
  }

  const active = partitions
    .map((partition) => ({
      partition: partition.partition,
      acked: Number(partition.ackedDelta ?? 0),
    }))
    .filter((partition) => partition.acked > 0);

  if (minStreamDirectActivePartitions !== undefined && active.length < minStreamDirectActivePartitions) {
    failures.push(
      `${label}: streamDirect active partitions ${active.length} < required ${formatNumber(minStreamDirectActivePartitions)}`,
    );
  }

  if (maxStreamDirectPartitionSkew !== undefined) {
    if (active.length < 2) {
      failures.push(`${label}: streamDirect partition skew requires at least 2 active partitions, got ${active.length}`);
      return;
    }
    const counts = active.map((partition) => partition.acked);
    const min = Math.min(...counts);
    const max = Math.max(...counts);
    const skew = max / min;
    if (skew > maxStreamDirectPartitionSkew) {
      failures.push(
        `${label}: streamDirect partition skew ${formatNumber(skew)} > required ${formatNumber(maxStreamDirectPartitionSkew)}`,
      );
    }
  }
}

function validateTelemetry(dir) {
  const telemetryFiles = walk(dir).filter((path) => path.endsWith("-telemetry.ndjson"));
  if (telemetryFiles.length === 0) {
    failures.push("missing telemetry ndjson artifact");
    return;
  }

  let sawDbPools = false;
  let sawStreamHealth = false;
  for (const path of telemetryFiles) {
    const lines = readFileSync(path, "utf8").split(/\r?\n/).filter(Boolean);
    for (const line of lines) {
      let sample;
      try {
        sample = JSON.parse(line);
      } catch (_error) {
        failures.push(`invalid telemetry line in ${path}`);
        continue;
      }
      const probes = sample.app?.probes ?? [];
      sawDbPools ||= probes.some((probe) => probe.name === "runtime.dbPools" && probe.ok);
      sawStreamHealth ||= probes.some((probe) => probe.name === "runtime.streamAckHealth" && probe.ok);
    }
  }

  if (!sawDbPools) {
    failures.push("telemetry did not capture a successful runtime.dbPools probe");
  }
  if (!sawStreamHealth) {
    failures.push("telemetry did not capture a successful runtime.streamAckHealth probe");
  }
}

function validateMaterializerDbDiagnostics(dir) {
  const summaryPath = join(dir, "venue-event-materializer-stress-diagnostics-summary.json");
  if (!existsSync(summaryPath)) {
    failures.push(`missing materializer DB diagnostics summary: ${summaryPath}`);
    return;
  }

  const summary = readJson(summaryPath);
  if (!summary.ok) {
    failures.push(`invalid materializer DB diagnostics summary: ${summaryPath}: ${summary.error}`);
    return;
  }

  const postgres = summary.json?.services?.postgres;
  if (!postgres?.ok) {
    failures.push("materializer DB diagnostics summary did not report services.postgres.ok=true");
  }
  const walBytes = Number(postgres?.wal?.walBytes ?? postgres?.unitMetrics?.walBytes);
  if (!Number.isFinite(walBytes) || walBytes <= 0) {
    failures.push("materializer DB diagnostics summary missing positive WAL bytes");
  }
  const walBytesPerAccepted = Number(postgres?.unitMetrics?.walBytesPerAcceptedCommand);
  if (!Number.isFinite(walBytesPerAccepted) || walBytesPerAccepted <= 0) {
    failures.push("materializer DB diagnostics summary missing positive WAL bytes per accepted command");
  }
  if (!Array.isArray(postgres?.topTablesByBytes) || postgres.topTablesByBytes.length === 0) {
    failures.push("materializer DB diagnostics summary missing topTablesByBytes");
  }

  const diagnosticsDir = join(dir, "venue-event-materializer-stress-diagnostics");
  const requiredFiles = [
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
  ];
  if (requirePgStatIo) {
    requiredFiles.push("pre-pg_stat_io.csv", "post-pg_stat_io.csv");
  }
  for (const file of requiredFiles) {
    const path = join(diagnosticsDir, file);
    if (!existsSync(path)) {
      failures.push(`missing materializer DB diagnostics artifact: ${path}`);
    }
  }
}

function checkZero(label, name, value) {
  const numeric = Number(value ?? 0);
  if (numeric !== 0) {
    failures.push(`${label}: ${name} must be 0, got ${numeric}`);
  }
}

function checkProjectorHealth(label, status) {
  if (!status || typeof status !== "object") {
    failures.push(`${label}: missing streamAckProjector.after status`);
    return;
  }
  if (status.enabled === false) {
    failures.push(`${label}: streamAckProjector.after.enabled must not be false`);
  }
  const metrics = status.metrics ?? {};
  if (Number(metrics.failed ?? 0) !== 0) {
    failures.push(`${label}: streamAckProjector.after.metrics.failed must be 0, got ${metrics.failed}`);
  }
  if (String(metrics.lastError ?? "").trim()) {
    failures.push(`${label}: streamAckProjector.after.metrics.lastError must be empty`);
  }
  for (const watermark of status.watermarks ?? []) {
    if (String(watermark.lastError ?? "").trim()) {
      failures.push(
        `${label}: streamAckProjector.after.watermark partition=${watermark.partition ?? "unknown"} lastError must be empty`,
      );
    }
  }
}

function hasBlockedInternalProbe(probes) {
  return flattenProbeStatuses(probes).some((probe) => {
    if (Number(probe?.status) === 403) return true;
    const payload = probe?.json ?? probe;
    return Number(payload?.status) === 403 || String(payload?.error ?? "").includes("internal HTTP route requires loopback access");
  });
}

function flattenProbeStatuses(value) {
  if (!value) return [];
  if (Array.isArray(value)) return value.flatMap((entry) => flattenProbeStatuses(entry));
  if (typeof value !== "object") return [];
  return [
    value,
    ...flattenProbeStatuses(value.before),
    ...flattenProbeStatuses(value.after),
    ...flattenProbeStatuses(value.probes),
  ];
}

function isMeasuredStressReport(json) {
  return Boolean(
    json &&
      typeof json === "object" &&
      json.config &&
      Number.isFinite(reportRatePerSecond(json)) &&
      Number.isFinite(Number(json.totalRequests)),
  );
}

function reportRatePerSecond(report) {
  const config = report?.config ?? {};
  const value = config.ratePerSecond ?? config.RatePerSecond;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : undefined;
}

function normalizeStatusCodes(raw) {
  return new Map(
    Object.entries(raw).map(([key, value]) => [Number(key), Number(value ?? 0)]),
  );
}

function reportMetric(report, unitMetricName, fallbackReportName) {
  const unitMetric = Number(report.unitMetrics?.[unitMetricName]);
  if (Number.isFinite(unitMetric)) return unitMetric;
  const fallback = fallbackReportName ? Number(report[fallbackReportName]) : Number.NaN;
  return Number.isFinite(fallback) ? fallback : 0;
}

function latencyMetric(report, name) {
  const value = Number(report.latencyMs?.[name] ?? report[name]);
  return Number.isFinite(value) ? value : 0;
}

function parseOptionalNumber(raw) {
  if (raw === undefined || String(raw).trim() === "") return undefined;
  const numeric = Number(String(raw).trim());
  return Number.isFinite(numeric) && numeric > 0 ? numeric : undefined;
}

function parseOptionalNonNegativeNumber(raw) {
  if (raw === undefined || String(raw).trim() === "") return undefined;
  const numeric = Number(String(raw).trim());
  return Number.isFinite(numeric) && numeric >= 0 ? numeric : undefined;
}

function formatNumber(value) {
  return Number(value).toFixed(2);
}

function walk(path) {
  const info = statSync(path);
  if (info.isFile()) return [path];
  return readdirSync(path).flatMap((entry) => walk(join(path, entry)));
}

function readJson(path) {
  try {
    return { ok: true, path, json: JSON.parse(readFileSync(path, "utf8")) };
  } catch (error) {
    return { ok: false, path, error: error?.message ?? String(error) };
  }
}

function parseCsvInts(raw) {
  return String(raw)
    .split(",")
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isFinite(value) && value > 0);
}

function finish() {
  writeEvidenceSummary();
  if (failures.length > 0) {
    console.error("DO benchmark report gates failed:");
    for (const failure of failures) {
      console.error(`  - ${failure}`);
    }
    printEvidenceSummary(console.error);
    process.exit(1);
  }
  printEvidenceSummary(console.log);
  console.log(`DO benchmark report gates passed for ${target}`);
}

function printEvidenceSummary(write) {
  if (evidenceRows.length === 0) return;
  write("DO benchmark evidence summary:");
  for (const row of evidenceRows) {
    const evidence = row.evidence;
    write(
      [
        `  - rate=${row.rate}`,
        `attempted=${evidence.attempted}`,
        `accepted=${evidence.accepted}`,
        `directAcked=${evidence.directAcked}`,
        `materialized=${evidence.materialized}`,
        `projected=${evidence.projected}`,
        `lag=${evidence.lag}`,
        `p95=${formatNumber(evidence.p95LatencyMs)}ms`,
        `p99=${formatNumber(evidence.p99LatencyMs)}ms`,
        `acceptedMaterializedGap=${evidence.gaps.acceptedToMaterialized}`,
      ].join(" "),
    );
  }
}

function writeEvidenceSummary() {
  if (evidenceRows.length === 0) return;
  try {
    if (statSync(target).isDirectory()) {
      writeFileSync(
        join(target, "do-benchmark-evidence-summary.json"),
        JSON.stringify(
          {
            generatedAt: new Date().toISOString(),
            reports: evidenceRows,
          },
          null,
          2,
        ),
      );
    }
  } catch {
    // Evidence summary is diagnostic; validation failures above remain authoritative.
  }
}
