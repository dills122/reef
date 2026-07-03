import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { basename, join } from "node:path";
import { execFile } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { promisify } from "node:util";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";
import {
  captureDbDiagnosticsLogs,
  captureDbDiagnosticsSnapshot,
  defaultDiagnosticSchemas,
} from "./lib/db-diagnostics.mjs";

loadDotEnv();
const execFileAsync = promisify(execFile);
const { runtimeUrl, engineUrl } = deriveDevUrls();
const duration = env("DEV_STRESS_DURATION", "30s");
const workers = env("DEV_STRESS_WORKERS", "12");
const traceLimit = env("DEV_STRESS_TRACE_CHECK_LIMIT", "100");
const out = env("DEV_STRESS_REPORT_OUT", "/tmp/reef-load-report-dev-stress.json");
const mode = env("DEV_STRESS_MODE", "strict-lifecycle");
const profile = env("DEV_STRESS_PROFILE", "default");
const runKind = env("DEV_STRESS_RUN_KIND", "stress");
const scenarioId = env("DEV_STRESS_SCENARIO_ID", `${mode}:${profile}`);
const sessionConfig = env("DEV_STRESS_SESSION_CONFIG", "");
const rateSchedule = env("DEV_STRESS_RATE_SCHEDULE", env("REEF_RATE_SCHEDULE", "drop"));
const telemetryIntervalMs = Number(env("DEV_STRESS_TELEMETRY_INTERVAL_MS", "1000"));
const minSuccessRatePct = Number(env("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "90"));
const sweepWorkers = parseCsvInts(env("DEV_STRESS_SWEEP_WORKERS", ""));
const rates = parseCsvInts(env("DEV_STRESS_RATES", "100,200,300,400"));
const artifactDir = env("DEV_STRESS_ARTIFACT_DIR", "/tmp");
const captureDbDiagnostics = env("DEV_STRESS_CAPTURE_DB_DIAGNOSTICS", "0") === "1";
const dbDiagnosticsService = env("DEV_STRESS_DB_SERVICE", "postgres");
const dbDiagnosticsUser = env("DEV_STRESS_DB_USER", "reef");
const dbDiagnosticsName = env("DEV_STRESS_DB_NAME", "reef");
const dbDiagnosticsSchemas = parseCsvStrings(
  env("DEV_STRESS_DB_SCHEMAS", env("DEV_STRESS_DB_SCHEMA", defaultDiagnosticSchemas.join(","))),
);
const dbDiagnosticsLogSince = env("DEV_STRESS_DB_LOG_SINCE", "30m");
const captureCommandAccounting = env("DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING", "1") !== "0";
const failOnAccountingGap = env("DEV_STRESS_FAIL_ON_ACCOUNTING_GAP", "0") === "1";
const captureStreamAckWorkerStats = env("DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS", "0") === "1";
const streamAckWorkerUrls = parseCsvStrings(
  env("DEV_STRESS_STREAM_ACK_WORKER_URLS", defaultStreamAckWorkerUrls(runtimeUrl).join(",")),
);
const captureStreamAckProjectorStats = env("DEV_STRESS_CAPTURE_STREAM_ACK_PROJECTOR", captureStreamAckWorkerStats ? "1" : "0") === "1";
const streamAckProjectorUrls = parseCsvStrings(
  env(
    "DEV_STRESS_STREAM_ACK_PROJECTOR_URLS",
    env("DEV_STRESS_STREAM_ACK_PROJECTOR_URL", defaultStreamAckProjectorUrls(runtimeUrl).join(",")),
  ),
);

const baseOut = out.replace(/\.json$/, "");
const reportBaseName = basename(baseOut);
mkdirSync(artifactDir, { recursive: true });
const telemetryOut = join(artifactDir, `${reportBaseName}-telemetry.ndjson`);
const recommendationOut = join(artifactDir, `${reportBaseName}-recommendation.json`);
const kpiOutJson = join(artifactDir, `${reportBaseName}-kpi.json`);
const kpiOutMd = join(artifactDir, `${reportBaseName}-kpi.md`);
const diagnosticsDir = join(artifactDir, `${reportBaseName}-diagnostics`);
const actionMix = resolveActionMix(profile);
const invalidIntentCodes = env(
  "DEV_STRESS_INVALID_INTENT_CODES",
  "INVALID_STATE,NOT_FOUND,VALIDATION_ERROR",
)
  .split(",")
  .map((value) => value.trim())
  .filter(Boolean);

console.log(`running stepped stress profile against ${runtimeUrl} (mode=${mode}, profile=${profile})`);

const telemetry = startTelemetryCapture({
  outPath: telemetryOut,
  intervalMs: telemetryIntervalMs,
  runtimeUrl,
  engineUrl,
});
if (captureDbDiagnostics) {
  resetDir(diagnosticsDir);
  await captureDbDiagnosticsSnapshot({
    diagnosticsDir,
    stage: "pre",
    service: dbDiagnosticsService,
    dbUser: dbDiagnosticsUser,
    dbName: dbDiagnosticsName,
    schemas: dbDiagnosticsSchemas,
  });
}
try {
  for (const rate of rates) {
    if (sweepWorkers.length > 0) {
      for (const workerCount of sweepWorkers) {
        const runId = runIdForStep({ rate, workerCount });
        await runStressStep({
          runtimeUrl,
          duration,
          workers: String(workerCount),
          rate,
          rateSchedule,
          mode,
          runId,
          runKind,
          scenarioId,
          traceLimit,
          actionMix,
          reportOut: `${baseOut}-rate-${rate}-workers-${workerCount}.json`,
        });
      }
    } else {
      const runId = runIdForStep({ rate, workerCount: workers });
      await runStressStep({
        runtimeUrl,
        duration,
        workers,
        rate,
        rateSchedule,
        mode,
        runId,
        runKind,
        scenarioId,
        traceLimit,
        actionMix,
        reportOut: `${baseOut}-rate-${rate}.json`,
      });
    }
  }
} finally {
  await telemetry.stop();
  if (captureDbDiagnostics) {
    await captureDbDiagnosticsSnapshot({
      diagnosticsDir,
      stage: "post",
      service: dbDiagnosticsService,
      dbUser: dbDiagnosticsUser,
      dbName: dbDiagnosticsName,
      schemas: dbDiagnosticsSchemas,
    });
    await captureDbDiagnosticsLogs({
      diagnosticsDir,
      service: dbDiagnosticsService,
      since: dbDiagnosticsLogSince,
    });
  }
}

console.log("stress run complete. reports:");
const reportFiles = [];
for (const rate of rates) {
  if (sweepWorkers.length > 0) {
    for (const workerCount of sweepWorkers) {
      const path = `${baseOut}-rate-${rate}-workers-${workerCount}.json`;
      reportFiles.push(path);
      console.log(`  ${path}`);
    }
  } else {
    const path = `${baseOut}-rate-${rate}.json`;
    reportFiles.push(path);
    console.log(`  ${path}`);
  }
}
console.log(`  ${telemetryOut}`);
if (captureDbDiagnostics) {
  console.log(`  ${diagnosticsDir}`);
}

const recommendation = buildRecommendation(reportFiles);
if (recommendation) {
  writeFileSync(recommendationOut, JSON.stringify(recommendation, null, 2));
  console.log("recommended settings:");
  console.log(
    `  workers=${recommendation.workers} rate=${recommendation.rate} throughput=${recommendation.throughputRps.toFixed(2)} accepted=${recommendation.acceptedRps.toFixed(2)} p95=${recommendation.p95Ms.toFixed(2)}ms p99=${recommendation.p99Ms.toFixed(2)}ms score=${recommendation.score.toFixed(2)}`,
  );
  console.log(`  ${recommendationOut}`);
}

const kpiSummary = buildKpiSummary(reportFiles, invalidIntentCodes);
if (kpiSummary) {
  writeFileSync(kpiOutJson, JSON.stringify(kpiSummary, null, 2));
  writeFileSync(kpiOutMd, toKpiMarkdown(kpiSummary));
  console.log("kpi summary:");
  console.log(
    `  best-throughput=${kpiSummary.bestByThroughput.rate}rps/${kpiSummary.bestByThroughput.workers}w throughput=${kpiSummary.bestByThroughput.throughputRps.toFixed(2)} success=${kpiSummary.bestByThroughput.endToEndSuccessRatePct.toFixed(2)}% valid-intent=${kpiSummary.bestByThroughput.validIntentSuccessRatePct.toFixed(2)}%`,
  );
  console.log(`  ${kpiOutJson}`);
  console.log(`  ${kpiOutMd}`);
}

const guardrail = evaluateSuccessGuardrail(reportFiles, minSuccessRatePct);
if (!guardrail.pass) {
  console.error(`success-rate guardrail failed (min=${minSuccessRatePct}%)`);
  for (const failure of guardrail.failures) {
    console.error(`  - ${failure}`);
  }
  process.exitCode = 1;
}

function parseCsvInts(raw) {
  return raw
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean)
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value) && value > 0);
}

function parseCsvStrings(raw) {
  return String(raw ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}

function runIdForStep({ rate, workerCount }) {
  return env(
    "DEV_STRESS_RUN_ID",
    `${reportBaseName}-rate-${rate}-workers-${workerCount}-${Date.now()}`,
  );
}

function resetDir(path) {
  rmSync(path, { recursive: true, force: true });
  mkdirSync(path, { recursive: true });
}

function resolveActionMix(profileName) {
  if (profileName === "strict-clean") {
    return { submit: "80", modify: "15", cancel: "5" };
  }
  if (profileName === "capacity-heavy") {
    return { submit: "68", modify: "24", cancel: "8" };
  }
  if (profileName === "abuse-trip") {
    return { submit: "35", modify: "45", cancel: "20" };
  }
  if (profileName === "stream-submit") {
    return { submit: "100", modify: "0", cancel: "0" };
  }
  return { submit: "70", modify: "20", cancel: "10" };
}

async function runStressStep({ runtimeUrl, duration, workers, rate, rateSchedule, mode, runId, runKind, scenarioId, traceLimit, actionMix, reportOut }) {
  console.log(`step rate=${rate} rps workers=${workers} runId=${runId}`);
  const beforeAccounting = captureCommandAccounting ? await sampleCommandAccounting(runtimeUrl, runId) : null;
  const beforeStreamWorkers = captureStreamAckWorkerStats ? await sampleStreamAckWorkers(runtimeUrl) : null;
  const beforeProjector = captureStreamAckProjectorStats ? await sampleStreamAckProjectors() : null;
  try {
    await run(
      "go",
      [
        "run",
        "./cmd/load-tester",
        ...(sessionConfig ? ["--session-config", sessionConfig] : []),
        "--base-url",
        runtimeUrl,
        "--duration",
        duration,
        "--workers",
        workers,
        "--rate",
        String(rate),
        "--rate-schedule",
        rateSchedule,
        "--mode",
        mode,
        "--run-id",
        runId,
        "--run-kind",
        runKind,
        "--scenario-id",
        scenarioId,
        "--submit-pct",
        actionMix.submit,
        "--modify-pct",
        actionMix.modify,
        "--cancel-pct",
        actionMix.cancel,
        "--profile-mm-pct",
        "35",
        "--profile-inst-pct",
        "30",
        "--profile-retail-pct",
        "25",
        "--profile-noise-pct",
        "10",
        "--trace-check-limit",
        traceLimit,
        "--pretty-summary",
        "--report-out",
        reportOut,
      ],
      { cwd: "services/simulator" },
    );
  } finally {
    if (captureCommandAccounting) {
      const afterAccounting = await sampleCommandAccounting(runtimeUrl, runId);
      attachCommandAccounting({ reportOut, duration, runId, beforeAccounting, afterAccounting });
    }
    if (captureStreamAckWorkerStats) {
      const afterStreamWorkers = await sampleStreamAckWorkers(runtimeUrl);
      attachStreamAckWorkerStats({ reportOut, duration, beforeStreamWorkers, afterStreamWorkers });
    }
    if (captureStreamAckProjectorStats) {
      const afterProjector = await sampleStreamAckProjectors();
      attachStreamAckProjectorStats({ reportOut, duration, beforeProjector, afterProjector });
    }
  }
}

async function sampleCommandAccounting(runtimeUrl, runId) {
  const encodedRunId = encodeURIComponent(runId);
  return requestAppProbe({
    name: "runtime.commandAccounting",
    url: `${runtimeUrl}/internal/commands/accounting?runId=${encodedRunId}`,
    captureJson: true,
  });
}

async function sampleStreamAckWorkers() {
  const probes = await Promise.all(
    streamAckWorkerUrls.map((baseUrl, index) =>
      requestAppProbe({
        name: `streamAckWorker.${index}.stats`,
        url: `${baseUrl}/internal/stream-ack/worker/stats`,
        captureJson: true,
      }),
    ),
  );
  return {
    ok: probes.every((probe) => probe.ok),
    status: probes.find((probe) => !probe.ok)?.status ?? 200,
    latencyMs: Math.max(0, ...probes.map((probe) => Number(probe.latencyMs ?? 0))),
    error: probes.find((probe) => probe.error)?.error ?? "",
    probes,
    json: aggregateStreamAckWorkerStats(probes),
  };
}

function defaultStreamAckWorkerUrls(runtimeUrl) {
  const parsed = new URL(runtimeUrl);
  const worker0Port = env("REEF_PLATFORM_WORKER_0_HOST_PORT", "8082");
  const worker1Port = env("REEF_PLATFORM_WORKER_1_HOST_PORT", "8083");
  return [
    `${parsed.protocol}//${parsed.hostname}:${worker0Port}`,
    `${parsed.protocol}//${parsed.hostname}:${worker1Port}`,
  ];
}

function defaultStreamAckProjectorUrls(runtimeUrl) {
  const parsed = new URL(runtimeUrl);
  const projector0Port = env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", env("REEF_PLATFORM_PROJECTOR_HOST_PORT", "8084"));
  const projector1Port = env("REEF_PLATFORM_PROJECTOR_1_HOST_PORT", "8085");
  return [
    `${parsed.protocol}//${parsed.hostname}:${projector0Port}`,
    `${parsed.protocol}//${parsed.hostname}:${projector1Port}`,
  ];
}

async function sampleStreamAckProjectors() {
  const probes = await Promise.all(
    streamAckProjectorUrls.map((baseUrl, index) =>
      requestAppProbe({
        name: `streamAckProjector.${index}.status`,
        url: `${baseUrl}/internal/projector/status`,
        captureJson: true,
      }),
    ),
  );
  return {
    ok: probes.every((probe) => probe.ok),
    status: probes.find((probe) => !probe.ok)?.status ?? 200,
    latencyMs: Math.max(0, ...probes.map((probe) => Number(probe.latencyMs ?? 0))),
    error: probes.find((probe) => probe.error)?.error ?? "",
    probes,
    json: aggregateStreamAckProjectorStatus(probes),
  };
}

function aggregateStreamAckProjectorStatus(probes) {
  const projectors = probes
    .map((probe, index) => ({ index, url: streamAckProjectorUrls[index], status: probe.json }))
    .filter((projector) => projector.status);
  const metrics = {
    projected: 0,
    failed: 0,
    emptyPolls: 0,
    lastProjectedAt: "",
    lastFailedAt: "",
    lastError: "",
  };
  let projectedCount = 0;
  let lag = 0;
  for (const projector of projectors) {
    const rawMetrics = projector.status.metrics ?? {};
    metrics.projected += Number(rawMetrics.projected ?? 0);
    metrics.failed += Number(rawMetrics.failed ?? 0);
    metrics.emptyPolls += Number(rawMetrics.emptyPolls ?? 0);
    metrics.lastProjectedAt = maxIso(metrics.lastProjectedAt, rawMetrics.lastProjectedAt ?? "");
    metrics.lastFailedAt = maxIso(metrics.lastFailedAt, rawMetrics.lastFailedAt ?? "");
    metrics.lastError = rawMetrics.lastError || metrics.lastError;
    projectedCount = Math.max(projectedCount, Number(projector.status.projectedCount ?? 0));
    lag += Number(projector.status.lag ?? 0);
  }
  return {
    enabled: projectors.some((projector) => projector.status.status === "running"),
    implementation: projectors.find((projector) => projector.status.implementation)?.status.implementation ?? "",
    projectionName: projectors.find((projector) => projector.status.projectionName)?.status.projectionName ?? "",
    projectedCount,
    lag,
    metrics,
    projectors: projectors.map((projector) => ({
      index: projector.index,
      url: projector.url,
      status: projector.status.status,
      partitions: projector.status.partitions ?? [],
      projectedCount: projector.status.projectedCount,
      lag: projector.status.lag,
      metrics: projector.status.metrics ?? {},
    })),
    watermarks: projectors.flatMap((projector) => projector.status.watermarks ?? []),
  };
}

function aggregateStreamAckWorkerStats(probes) {
  const workerStats = probes
    .map((probe, index) => ({ index, url: streamAckWorkerUrls[index], stats: probe.json }))
    .filter((worker) => worker.stats);
  const metrics = sumStreamWorkerMetrics(workerStats.map((worker) => worker.stats.metrics));
  const partitionMetrics = mergeStreamWorkerPartitions(
    workerStats.flatMap((worker) => worker.stats.partitionMetrics ?? []),
  );
  return {
    enabled: workerStats.some((worker) => worker.stats.enabled === true),
    processingMode: workerStats.find((worker) => worker.stats.processingMode)?.stats.processingMode ?? "",
    workers: workerStats.map((worker) => ({
      index: worker.index,
      url: worker.url,
      enabled: worker.stats.enabled,
      partitions: worker.stats.partitions ?? [],
      metrics: worker.stats.metrics ?? {},
    })),
    partitions: [...new Set(workerStats.flatMap((worker) => worker.stats.partitions ?? []))]
      .map((partition) => Number(partition))
      .filter((partition) => Number.isFinite(partition))
      .sort((a, b) => a - b),
    metrics,
    partitionMetrics,
    consumerMetrics: workerStats.flatMap((worker) => worker.stats.consumerMetrics ?? []),
  };
}

function sumStreamWorkerMetrics(metricsList) {
  const totals = {
    fetched: 0,
    completed: 0,
    failed: 0,
    ackFailed: 0,
    unsupported: 0,
    emptyPolls: 0,
    lastFetchedAt: "",
    lastCompletedAt: "",
    lastFailedAt: "",
    lastAckFailedAt: "",
    lastError: "",
  };
  for (const metrics of metricsList) {
    if (!metrics) continue;
    totals.fetched += Number(metrics.fetched ?? 0);
    totals.completed += Number(metrics.completed ?? 0);
    totals.failed += Number(metrics.failed ?? 0);
    totals.ackFailed += Number(metrics.ackFailed ?? 0);
    totals.unsupported += Number(metrics.unsupported ?? 0);
    totals.emptyPolls += Number(metrics.emptyPolls ?? 0);
    totals.lastFetchedAt = maxIso(totals.lastFetchedAt, metrics.lastFetchedAt ?? "");
    totals.lastCompletedAt = maxIso(totals.lastCompletedAt, metrics.lastCompletedAt ?? "");
    totals.lastFailedAt = maxIso(totals.lastFailedAt, metrics.lastFailedAt ?? "");
    totals.lastAckFailedAt = maxIso(totals.lastAckFailedAt, metrics.lastAckFailedAt ?? "");
    totals.lastError = metrics.lastError || totals.lastError;
  }
  return totals;
}

function mergeStreamWorkerPartitions(partitions) {
  const byPartition = new Map();
  for (const raw of partitions) {
    const partition = Number(raw.partition);
    if (!Number.isFinite(partition)) continue;
    const current = byPartition.get(partition) ?? {
      partition,
      fetched: 0,
      completed: 0,
      failed: 0,
      ackFailed: 0,
      unsupported: 0,
      localInFlight: 0,
      maxDeliveredCount: 0,
      lastFetchedStreamSequence: 0,
      lastCompletedStreamSequence: 0,
      lastFetchedAt: "",
      lastCompletedAt: "",
      lastFailedAt: "",
      lastAckFailedAt: "",
      oldestLocalInFlightAt: "",
      oldestLocalInFlightAgeMs: 0,
      lastError: "",
    };
    current.fetched += Number(raw.fetched ?? 0);
    current.completed += Number(raw.completed ?? 0);
    current.failed += Number(raw.failed ?? 0);
    current.ackFailed += Number(raw.ackFailed ?? 0);
    current.unsupported += Number(raw.unsupported ?? 0);
    current.localInFlight += Number(raw.localInFlight ?? 0);
    current.maxDeliveredCount = Math.max(current.maxDeliveredCount, Number(raw.maxDeliveredCount ?? 0));
    current.lastFetchedStreamSequence = Math.max(
      current.lastFetchedStreamSequence,
      Number(raw.lastFetchedStreamSequence ?? 0),
    );
    current.lastCompletedStreamSequence = Math.max(
      current.lastCompletedStreamSequence,
      Number(raw.lastCompletedStreamSequence ?? 0),
    );
    current.lastFetchedAt = maxIso(current.lastFetchedAt, raw.lastFetchedAt ?? "");
    current.lastCompletedAt = maxIso(current.lastCompletedAt, raw.lastCompletedAt ?? "");
    current.lastFailedAt = maxIso(current.lastFailedAt, raw.lastFailedAt ?? "");
    current.lastAckFailedAt = maxIso(current.lastAckFailedAt, raw.lastAckFailedAt ?? "");
    current.oldestLocalInFlightAgeMs = Math.max(
      current.oldestLocalInFlightAgeMs,
      Number(raw.oldestLocalInFlightAgeMs ?? 0),
    );
    current.oldestLocalInFlightAt =
      current.oldestLocalInFlightAt || raw.oldestLocalInFlightAt || "";
    current.lastError = raw.lastError || current.lastError;
    byPartition.set(partition, current);
  }
  return [...byPartition.values()].sort((a, b) => a.partition - b.partition);
}

function maxIso(left, right) {
  if (!left) return right || "";
  if (!right) return left;
  return right > left ? right : left;
}

function attachStreamAckWorkerStats({ reportOut, duration, beforeStreamWorkers, afterStreamWorkers }) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    const before = beforeStreamWorkers?.json ?? null;
    const after = afterStreamWorkers?.json ?? null;
    const durationSeconds = Number(report.durationSeconds ?? 0) || parseDurationSeconds(duration);
    const delta = streamWorkerDelta(before, after, durationSeconds);
    report.streamAckWorkers = {
      before,
      after,
      delta,
      probes: {
        before: accountingProbeSummary(beforeStreamWorkers),
        after: accountingProbeSummary(afterStreamWorkers),
      },
    };
    writeFileSync(reportOut, JSON.stringify(report, null, 2));
    if (delta) {
      const hotPartitions = delta.partitionDeltas
        .slice()
        .sort((a, b) => b.completedDelta - a.completedDelta)
        .slice(0, 4)
        .map((partition) => `p${partition.partition}:${partition.completedDelta}`)
        .join(" ");
      console.log(
        `  stream-workers fetchedDelta=${delta.fetchedDelta} completedDelta=${delta.completedDelta} failedDelta=${delta.failedDelta} ackFailedDelta=${delta.ackFailedDelta} completedRps=${delta.completedRps.toFixed(2)} hotPartitions=${hotPartitions}`,
      );
    }
  } catch (error) {
    console.warn(`  stream-worker stats unavailable: ${error?.message ?? error}`);
  }
}

function attachStreamAckProjectorStats({ reportOut, duration, beforeProjector, afterProjector }) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    const before = beforeProjector?.json ?? null;
    const after = afterProjector?.json ?? null;
    const durationSeconds = Number(report.durationSeconds ?? 0) || parseDurationSeconds(duration);
    const projectedDelta = Number(after?.metrics?.projected ?? 0) - Number(before?.metrics?.projected ?? 0);
    const lagDelta = Number(after?.lag ?? 0) - Number(before?.lag ?? 0);
    report.streamAckProjector = {
      before,
      after,
      delta: {
        projectedDelta,
        projectedRps: durationSeconds > 0 ? projectedDelta / durationSeconds : 0,
        lagDelta,
        beforeLag: Number(before?.lag ?? 0),
        afterLag: Number(after?.lag ?? 0),
      },
      probes: {
        before: accountingProbeSummary(beforeProjector),
        after: accountingProbeSummary(afterProjector),
      },
    };
    writeFileSync(reportOut, JSON.stringify(report, null, 2));
    console.log(
      `  projector projectedDelta=${projectedDelta} projectedRps=${report.streamAckProjector.delta.projectedRps.toFixed(2)} afterLag=${report.streamAckProjector.delta.afterLag}`,
    );
  } catch (error) {
    console.warn(`  projector stats unavailable: ${error?.message ?? error}`);
  }
}

function streamWorkerDelta(before, after, durationSeconds) {
  const beforeMetrics = before?.metrics;
  const afterMetrics = after?.metrics;
  if (!beforeMetrics || !afterMetrics) return null;
  const fetchedDelta = Number(afterMetrics.fetched ?? 0) - Number(beforeMetrics.fetched ?? 0);
  const completedDelta = Number(afterMetrics.completed ?? 0) - Number(beforeMetrics.completed ?? 0);
  const failedDelta = Number(afterMetrics.failed ?? 0) - Number(beforeMetrics.failed ?? 0);
  const ackFailedDelta = Number(afterMetrics.ackFailed ?? 0) - Number(beforeMetrics.ackFailed ?? 0);
  const unsupportedDelta = Number(afterMetrics.unsupported ?? 0) - Number(beforeMetrics.unsupported ?? 0);
  const partitionDeltas = streamWorkerPartitionDeltas(before, after, durationSeconds);
  return {
    fetchedDelta,
    completedDelta,
    failedDelta,
    ackFailedDelta,
    unsupportedDelta,
    fetchedRps: durationSeconds > 0 ? fetchedDelta / durationSeconds : 0,
    completedRps: durationSeconds > 0 ? completedDelta / durationSeconds : 0,
    partitionDeltas,
    consumerMetrics: after?.consumerMetrics ?? [],
  };
}

function streamWorkerPartitionDeltas(before, after, durationSeconds) {
  const beforeByPartition = new Map(
    (before?.partitionMetrics ?? []).map((partition) => [Number(partition.partition), partition]),
  );
  return (after?.partitionMetrics ?? [])
    .map((afterPartition) => {
      const partition = Number(afterPartition.partition);
      const beforePartition = beforeByPartition.get(partition) ?? {};
      const fetchedDelta = Number(afterPartition.fetched ?? 0) - Number(beforePartition.fetched ?? 0);
      const completedDelta = Number(afterPartition.completed ?? 0) - Number(beforePartition.completed ?? 0);
      const failedDelta = Number(afterPartition.failed ?? 0) - Number(beforePartition.failed ?? 0);
      const ackFailedDelta = Number(afterPartition.ackFailed ?? 0) - Number(beforePartition.ackFailed ?? 0);
      const unsupportedDelta = Number(afterPartition.unsupported ?? 0) - Number(beforePartition.unsupported ?? 0);
      return {
        partition,
        fetchedDelta,
        completedDelta,
        failedDelta,
        ackFailedDelta,
        unsupportedDelta,
        fetchedRps: durationSeconds > 0 ? fetchedDelta / durationSeconds : 0,
        completedRps: durationSeconds > 0 ? completedDelta / durationSeconds : 0,
        localInFlightAfter: Number(afterPartition.localInFlight ?? 0),
        lastFetchedStreamSequence: Number(afterPartition.lastFetchedStreamSequence ?? 0),
        lastCompletedStreamSequence: Number(afterPartition.lastCompletedStreamSequence ?? 0),
        maxDeliveredCount: Number(afterPartition.maxDeliveredCount ?? 0),
      };
    })
    .sort((a, b) => a.partition - b.partition);
}

function attachCommandAccounting({ reportOut, duration, runId, beforeAccounting, afterAccounting }) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    const before = beforeAccounting?.json ?? null;
    const after = afterAccounting?.json ?? null;
    const durationSeconds = Number(report.durationSeconds ?? 0) || parseDurationSeconds(duration);
    const delta = commandAccountingDelta(before, after, durationSeconds);
    report.commandAccounting = {
      runId,
      before,
      after,
      delta,
      probes: {
        before: accountingProbeSummary(beforeAccounting),
        after: accountingProbeSummary(afterAccounting),
      },
    };
    writeFileSync(reportOut, JSON.stringify(report, null, 2));
    if (delta) {
      console.log(
        `  command-accounting acceptedDelta=${delta.acceptedDelta} terminalDelta=${delta.terminalDelta} active=${delta.activeAfter} gap=${delta.accountingGapAfter} completedRps=${delta.completedRps.toFixed(2)}`,
      );
      if (failOnAccountingGap && delta.accountingGapAfter !== 0) {
        throw new Error(`command accounting gap for run ${runId}: ${delta.accountingGapAfter}`);
      }
    }
  } catch (error) {
    if (failOnAccountingGap) {
      throw error;
    }
    console.warn(`  command-accounting unavailable: ${error?.message ?? error}`);
  }
}

function commandAccountingDelta(before, after, durationSeconds) {
  if (!before?.available || !after?.available) return null;
  const acceptedDelta = Number(after.accepted ?? 0) - Number(before.accepted ?? 0);
  const completedDelta = Number(after.completed ?? 0) - Number(before.completed ?? 0);
  const failedDelta = Number(after.failed ?? 0) - Number(before.failed ?? 0);
  const terminalDelta = completedDelta + failedDelta;
  return {
    acceptedDelta,
    completedDelta,
    failedDelta,
    terminalDelta,
    activeAfter: Number(after.active ?? 0),
    receivedAfter: Number(after.received ?? 0),
    processingAfter: Number(after.processing ?? 0),
    staleProcessingAfter: Number(after.staleProcessing ?? 0),
    accountingGapAfter: Number(after.accountingGap ?? 0),
    acceptedRps: durationSeconds > 0 ? acceptedDelta / durationSeconds : 0,
    completedRps: durationSeconds > 0 ? terminalDelta / durationSeconds : 0,
  };
}

function accountingProbeSummary(probe) {
  if (!probe) return null;
  return {
    ok: probe.ok,
    status: probe.status,
    latencyMs: probe.latencyMs,
    error: probe.error,
    bodyError: probe.bodyError,
  };
}

function parseDurationSeconds(raw) {
  const value = String(raw ?? "").trim().toLowerCase();
  const match = value.match(/^(\d+(?:\.\d+)?)(ms|s|m|h)$/);
  if (!match) return 0;
  const amount = Number(match[1]);
  const unit = match[2];
  const multiplier = { ms: 0.001, s: 1, m: 60, h: 3600 }[unit] ?? 0;
  return amount * multiplier;
}

function startTelemetryCapture({ outPath, intervalMs, runtimeUrl, engineUrl }) {
  let stopped = false;
  const loop = (async () => {
    while (!stopped) {
      const sampledAt = new Date().toISOString();
      const [docker, app] = await Promise.all([
        sampleDockerStats(sampledAt),
        sampleAppEndpoints(sampledAt, runtimeUrl, engineUrl),
      ]);
      const sample = { sampledAt, docker, app };
      appendFileSync(outPath, JSON.stringify(sample) + "\n");
      await sleep(intervalMs);
    }
  })();
  return {
    async stop() {
      stopped = true;
      await loop;
    },
  };
}

async function sampleAppEndpoints(sampledAt, runtimeUrl, engineUrl) {
  const probes = [
    { name: "runtime.health", url: `${runtimeUrl}/health` },
    { name: "runtime.metrics", url: `${runtimeUrl}/actuator/prometheus` },
    { name: "runtime.hotPath", url: `${runtimeUrl}/internal/perf/hot-path`, captureJson: true },
    { name: "runtime.dbPools", url: `${runtimeUrl}/internal/perf/db-pools`, captureJson: true },
    { name: "runtime.asyncCommands", url: `${runtimeUrl}/internal/commands/async/stats`, captureJson: true },
    { name: "runtime.streamAckHealth", url: `${runtimeUrl}/internal/stream-ack/health`, captureJson: true },
    { name: "runtime.streamAckWorkers", url: `${runtimeUrl}/internal/stream-ack/worker/stats`, captureJson: true },
    ...streamAckProjectorUrls.map((baseUrl, index) => ({
      name: `streamAckProjector.${index}.status`,
      url: `${baseUrl}/internal/projector/status`,
      captureJson: true,
    })),
    ...streamAckWorkerUrls.map((baseUrl, index) => ({
      name: `streamAckWorker.${index}.stats`,
      url: `${baseUrl}/internal/stream-ack/worker/stats`,
      captureJson: true,
    })),
    { name: "engine.health", url: `${engineUrl}/health` },
    { name: "engine.metrics", url: `${engineUrl}/actuator/prometheus` },
  ];
  const results = [];
  for (const probe of probes) {
    results.push(await requestAppProbe(probe));
  }
  return { sampledAt, probes: results };
}

async function requestAppProbe(probe) {
  const started = Date.now();
  return new Promise((resolve) => {
    const url = new URL(probe.url);
    const client = url.protocol === "https:" ? https : http;
    const req = client.request(url, { method: "GET", timeout: 2000 }, (response) => {
      const chunks = [];
      let bytes = 0;
      response.on("data", (chunk) => {
        bytes += chunk.length;
        if (bytes <= 1024 * 1024) {
          chunks.push(chunk);
        }
      });
      response.on("end", () => {
        const result = {
          name: probe.name,
          status: response.statusCode,
          ok: response.statusCode >= 200 && response.statusCode < 300,
          latencyMs: Date.now() - started,
        };
        if (probe.captureJson) {
          try {
            result.json = JSON.parse(Buffer.concat(chunks).toString("utf8"));
          } catch (error) {
            result.bodyError = String(error.message || error);
          }
        }
        resolve(result);
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error("timeout"));
    });
    req.on("error", (error) => {
      resolve({
        name: probe.name,
        ok: false,
        latencyMs: Date.now() - started,
        error: String(error.message || error),
      });
    });
    req.end();
  });
}

async function sampleDockerStats(sampledAt) {
  try {
    const { stdout } = await execFileAsync("docker", ["compose", "-f", "docker-compose.yml", "ps", "--services"], {
      cwd: process.cwd(),
    });
    const services = stdout
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean);
    if (services.length === 0) {
      return { sampledAt, services: [] };
    }
    const stats = [];
    for (const svc of services) {
      try {
        const { stdout: statOut } = await execFileAsync(
          "docker",
          ["compose", "-f", "docker-compose.yml", "stats", svc, "--no-stream", "--format", "json"],
          { cwd: process.cwd() },
        );
        const line = statOut.trim();
        if (line) stats.push(JSON.parse(line));
      } catch (error) {
        stats.push({ Name: svc, error: String(error.message || error) });
      }
    }
    return { sampledAt, services: stats };
  } catch (error) {
    return { sampledAt, error: String(error.message || error) };
  }
}

function buildRecommendation(reportFiles) {
  const rows = [];
  for (const path of reportFiles) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      const filename = basename(path);
      const workerMatch = filename.match(/workers-(\d+)\.json$/);
      const rateMatch = filename.match(/rate-(\d+)/);
      rows.push({
        path,
        workers: workerMatch ? Number(workerMatch[1]) : Number(report.config?.workers ?? 0),
        rate: rateMatch ? Number(rateMatch[1]) : Number(report.config?.ratePerSecond ?? 0),
        throughputRps: Number(report.throughputRps ?? 0),
        acceptedRps: Number(report.acceptedBusinessOpsRps ?? 0),
        p95Ms: Number(report.latencyMs?.p95 ?? 0),
        p99Ms: Number(report.latencyMs?.p99 ?? 0),
        successRatePct:
          Number(report.totalRequests ?? 0) > 0
            ? (Number(report.totalSuccess ?? 0) / Number(report.totalRequests)) * 100
            : 0,
      });
    } catch {
      // skip unreadable report
    }
  }
  if (rows.length === 0) return null;
  const latencyTargetMs = 100;
  const acceptable = rows.filter((row) => row.p95Ms <= latencyTargetMs && row.p99Ms <= latencyTargetMs * 1.5);
  const candidates = acceptable.length > 0 ? acceptable : rows;
  const scored = candidates.map((row) => ({
    ...row,
    score:
      row.acceptedRps -
      Math.max(0, row.p95Ms-latencyTargetMs)*0.75 -
      Math.max(0, row.p99Ms-latencyTargetMs*1.5)*0.5,
  }));
  scored.sort((a, b) => {
    if (a.score === b.score) return a.p95Ms - b.p95Ms;
    return b.score - a.score;
  });
  return {
    selectedAt: new Date().toISOString(),
    latencyTargetMs,
    totalSamples: rows.length,
    workers: scored[0].workers,
    rate: scored[0].rate,
    throughputRps: scored[0].throughputRps,
    acceptedRps: scored[0].acceptedRps,
    p95Ms: scored[0].p95Ms,
    p99Ms: scored[0].p99Ms,
    score: scored[0].score,
    topSamples: scored.slice(0, 5),
  };
}

function evaluateSuccessGuardrail(reportFiles, minSuccessRatePct) {
  if (!Number.isFinite(minSuccessRatePct) || minSuccessRatePct <= 0) {
    return { pass: true, failures: [] };
  }
  const failures = [];
  for (const path of reportFiles) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      const successRatePct =
        Number(report.totalRequests ?? 0) > 0
          ? (Number(report.totalSuccess ?? 0) / Number(report.totalRequests)) * 100
          : 0;
      if (successRatePct < minSuccessRatePct) {
        failures.push(`${path}: success-rate ${successRatePct.toFixed(2)}% < ${minSuccessRatePct}%`);
      }
    } catch {
      failures.push(`${path}: unable to parse report for guardrail check`);
    }
  }
  return { pass: failures.length === 0, failures };
}

function buildKpiSummary(reportFiles, invalidCodes) {
  const samples = [];
  for (const path of reportFiles) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      const filename = basename(path);
      const workerMatch = filename.match(/workers-(\d+)\.json$/);
      const rateMatch = filename.match(/rate-(\d+)/);
      const quality = qualityFromReport(report, invalidCodes);
      const traceChecked = Number(report.traceChecks?.checked ?? 0);
      const tracePass = Number(report.traceChecks?.pass ?? 0);
      samples.push({
        path,
        rate: rateMatch ? Number(rateMatch[1]) : Number(report.config?.ratePerSecond ?? 0),
        workers: workerMatch ? Number(workerMatch[1]) : Number(report.config?.workers ?? 0),
        throughputRps: Number(report.throughputRps ?? 0),
        acceptedBusinessOpsRps: Number(report.acceptedBusinessOpsRps ?? 0),
        endToEndSuccessRatePct: quality.endToEndSuccessRatePct,
        validIntentSuccessRatePct: quality.validIntentSuccessRatePct,
        invalidIntentRatePct: quality.invalidIntentRatePct,
        systemFailureRateProxyPct: quality.systemFailureRatePct,
        tracePassRatePct: traceChecked > 0 ? (tracePass / traceChecked) * 100 : 100,
        p95LatencyMs: Number(report.latencyMs?.p95 ?? 0),
        p99LatencyMs: Number(report.latencyMs?.p99 ?? 0),
        rejectTaxonomy: report.rejectTaxonomy ?? [],
      });
    } catch {
      // skip unreadable report
    }
  }
  if (samples.length === 0) return null;

  const bestByThroughput = [...samples].sort((a, b) => b.throughputRps - a.throughputRps)[0];
  const bestByAccepted = [...samples].sort((a, b) => b.acceptedBusinessOpsRps - a.acceptedBusinessOpsRps)[0];
  const quality90 = [...samples]
    .filter((sample) => sample.endToEndSuccessRatePct >= 90)
    .sort((a, b) => b.throughputRps - a.throughputRps)[0] ?? null;
  const quality95 = [...samples]
    .filter((sample) => sample.endToEndSuccessRatePct >= 95)
    .sort((a, b) => b.throughputRps - a.throughputRps)[0] ?? null;

  const averages = {
    throughputRps: avg(samples.map((sample) => sample.throughputRps)),
    acceptedBusinessOpsRps: avg(samples.map((sample) => sample.acceptedBusinessOpsRps)),
    endToEndSuccessRatePct: avg(samples.map((sample) => sample.endToEndSuccessRatePct)),
    validIntentSuccessRatePct: avg(samples.map((sample) => sample.validIntentSuccessRatePct)),
    invalidIntentRatePct: avg(samples.map((sample) => sample.invalidIntentRatePct)),
    systemFailureRateProxyPct: avg(samples.map((sample) => sample.systemFailureRateProxyPct)),
    tracePassRatePct: avg(samples.map((sample) => sample.tracePassRatePct)),
    p95LatencyMs: avg(samples.map((sample) => sample.p95LatencyMs)),
    p99LatencyMs: avg(samples.map((sample) => sample.p99LatencyMs)),
  };

  return {
    generatedAt: new Date().toISOString(),
    invalidIntentCodes: invalidCodes,
    sampleCount: samples.length,
    averages,
    bestByThroughput,
    bestByAccepted,
    qualityCap90: quality90,
    qualityCap95: quality95,
    samples,
  };
}

function qualityFromReport(report, invalidCodes) {
  if (report.quality && typeof report.quality === "object") {
    return {
      endToEndSuccessRatePct: Number(report.quality.endToEndSuccessRatePct ?? 0),
      validIntentSuccessRatePct: Number(report.quality.validIntentSuccessRatePct ?? 0),
      invalidIntentRatePct: Number(report.quality.invalidIntentRatePct ?? 0),
      systemFailureRatePct: Number(report.quality.systemFailureRatePct ?? 0),
    };
  }
  const totalRequests = Number(report.totalRequests ?? 0);
  const totalSuccess = Number(report.totalSuccess ?? 0);
  const totalFailures = Number(report.totalFailures ?? 0);
  const invalidIntentRejectCount = invalidCodes.reduce(
    (acc, code) => acc + rejectCount(report, code),
    0,
  );
  const validIntentRequestCount = Math.max(totalRequests - invalidIntentRejectCount, 0);
  const systemFailureProxyCount = Math.max(totalFailures - invalidIntentRejectCount, 0);
  return {
    endToEndSuccessRatePct: totalRequests > 0 ? (totalSuccess / totalRequests) * 100 : 0,
    validIntentSuccessRatePct:
      validIntentRequestCount > 0 ? (totalSuccess / validIntentRequestCount) * 100 : 0,
    invalidIntentRatePct: totalRequests > 0 ? (invalidIntentRejectCount / totalRequests) * 100 : 0,
    systemFailureRatePct: totalRequests > 0 ? (systemFailureProxyCount / totalRequests) * 100 : 0,
  };
}

function rejectCount(report, code) {
  const rows = Array.isArray(report.rejectTaxonomy) ? report.rejectTaxonomy : [];
  const hit = rows.find((row) => row?.code === code);
  return Number(hit?.count ?? 0);
}

function avg(values) {
  if (values.length === 0) return 0;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function toKpiMarkdown(kpiSummary) {
  const lines = [];
  lines.push("# Stress KPI Summary");
  lines.push("");
  lines.push(`Generated: ${kpiSummary.generatedAt}`);
  lines.push(`Samples: ${kpiSummary.sampleCount}`);
  lines.push(`Invalid-intent reject codes: ${kpiSummary.invalidIntentCodes.join(", ")}`);
  lines.push("");
  lines.push("## Averages");
  lines.push(`- ingress throughput: ${kpiSummary.averages.throughputRps.toFixed(2)} rps`);
  lines.push(`- accepted throughput: ${kpiSummary.averages.acceptedBusinessOpsRps.toFixed(2)} rps`);
  lines.push(`- end-to-end success-rate: ${kpiSummary.averages.endToEndSuccessRatePct.toFixed(2)}%`);
  lines.push(`- valid-intent success-rate (proxy): ${kpiSummary.averages.validIntentSuccessRatePct.toFixed(2)}%`);
  lines.push(`- invalid-intent rate: ${kpiSummary.averages.invalidIntentRatePct.toFixed(2)}%`);
  lines.push(`- system-failure rate (proxy): ${kpiSummary.averages.systemFailureRateProxyPct.toFixed(2)}%`);
  lines.push(`- trace pass-rate: ${kpiSummary.averages.tracePassRatePct.toFixed(2)}%`);
  lines.push(`- p95 latency: ${kpiSummary.averages.p95LatencyMs.toFixed(2)} ms`);
  lines.push(`- p99 latency: ${kpiSummary.averages.p99LatencyMs.toFixed(2)} ms`);
  lines.push("");
  lines.push("## Best Samples");
  lines.push(
    `- best throughput: rate=${kpiSummary.bestByThroughput.rate} workers=${kpiSummary.bestByThroughput.workers} throughput=${kpiSummary.bestByThroughput.throughputRps.toFixed(2)} accepted=${kpiSummary.bestByThroughput.acceptedBusinessOpsRps.toFixed(2)} success=${kpiSummary.bestByThroughput.endToEndSuccessRatePct.toFixed(2)}%`,
  );
  lines.push(
    `- best accepted: rate=${kpiSummary.bestByAccepted.rate} workers=${kpiSummary.bestByAccepted.workers} throughput=${kpiSummary.bestByAccepted.throughputRps.toFixed(2)} accepted=${kpiSummary.bestByAccepted.acceptedBusinessOpsRps.toFixed(2)} success=${kpiSummary.bestByAccepted.endToEndSuccessRatePct.toFixed(2)}%`,
  );
  lines.push(
    `- quality cap >=90%: ${
      kpiSummary.qualityCap90
        ? `rate=${kpiSummary.qualityCap90.rate} workers=${kpiSummary.qualityCap90.workers} throughput=${kpiSummary.qualityCap90.throughputRps.toFixed(2)}`
        : "not reached"
    }`,
  );
  lines.push(
    `- quality cap >=95%: ${
      kpiSummary.qualityCap95
        ? `rate=${kpiSummary.qualityCap95.rate} workers=${kpiSummary.qualityCap95.workers} throughput=${kpiSummary.qualityCap95.throughputRps.toFixed(2)}`
        : "not reached"
    }`,
  );
  lines.push("");
  lines.push("## Per Sample");
  lines.push("| Rate | Workers | Throughput RPS | Accepted RPS | E2E Success % | Valid-Intent Success % | Invalid-Intent % | System-Failure % (proxy) | p95 ms | p99 ms |");
  lines.push("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
  for (const sample of kpiSummary.samples) {
    lines.push(
      `| ${sample.rate} | ${sample.workers} | ${sample.throughputRps.toFixed(2)} | ${sample.acceptedBusinessOpsRps.toFixed(2)} | ${sample.endToEndSuccessRatePct.toFixed(2)} | ${sample.validIntentSuccessRatePct.toFixed(2)} | ${sample.invalidIntentRatePct.toFixed(2)} | ${sample.systemFailureRateProxyPct.toFixed(2)} | ${sample.p95LatencyMs.toFixed(2)} | ${sample.p99LatencyMs.toFixed(2)} |`,
    );
  }
  lines.push("");
  return lines.join("\n");
}
