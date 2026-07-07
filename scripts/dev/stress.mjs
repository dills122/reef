import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { basename, join, resolve } from "node:path";
import { execFile } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { promisify } from "node:util";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";
import {
  captureDbDiagnosticsLogs,
  captureDbDiagnosticsSnapshot,
  defaultDiagnosticSchemas,
  summarizeDiagnosticsDelta,
} from "./lib/db-diagnostics.mjs";
import { canonicalEvidenceSummary } from "./lib/report-taxonomy.mjs";

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
const commandTransport = env("DEV_STRESS_TRANSPORT", env("REEF_TRANSPORT", "http"));
const streamAddress = env("DEV_STRESS_STREAM_ADDRESS", env("REEF_STREAM_ADDRESS", "127.0.0.1:8090"));
const rateSchedule = env("DEV_STRESS_RATE_SCHEDULE", env("REEF_RATE_SCHEDULE", "drop"));
const rateQueueDepth = env("DEV_STRESS_RATE_QUEUE_DEPTH", env("REEF_RATE_QUEUE_DEPTH", ""));
const telemetryIntervalMs = Number(env("DEV_STRESS_TELEMETRY_INTERVAL_MS", "1000"));
const minSuccessRatePct = Number(env("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "90"));
const sweepWorkers = parseCsvInts(env("DEV_STRESS_SWEEP_WORKERS", ""));
const rates = parseCsvInts(env("DEV_STRESS_RATES", "100,200,300,400"));
const artifactDir = env("DEV_STRESS_ARTIFACT_DIR", "/tmp");
const captureDbDiagnostics = env("DEV_STRESS_CAPTURE_DB_DIAGNOSTICS", "0") === "1";
const dbDiagnosticsService = env("DEV_STRESS_DB_SERVICE", "postgres");
const dbDiagnosticsServices = parseCsvStrings(env("DEV_STRESS_DB_SERVICES", dbDiagnosticsService));
const dbDiagnosticsUser = env("DEV_STRESS_DB_USER", "reef");
const dbDiagnosticsName = env("DEV_STRESS_DB_NAME", "reef");
const dbDiagnosticsSchemas = parseCsvStrings(
  env("DEV_STRESS_DB_SCHEMAS", env("DEV_STRESS_DB_SCHEMA", defaultDiagnosticSchemas.join(","))),
);
const dbDiagnosticsLogSince = env("DEV_STRESS_DB_LOG_SINCE", "30m");
const captureCommandAccounting = env("DEV_STRESS_CAPTURE_COMMAND_ACCOUNTING", "1") !== "0";
const failOnAccountingGap = env("DEV_STRESS_FAIL_ON_ACCOUNTING_GAP", "0") === "1";
const captureHotPath = env("DEV_STRESS_CAPTURE_HOT_PATH", "1") !== "0";
const captureStreamAckWorkerStats = env("DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS", "0") === "1";
const failOnStreamAckWorkerFailures =
  env("DEV_STRESS_FAIL_ON_STREAM_ACK_WORKER_FAILURES", captureStreamAckWorkerStats ? "1" : "0") === "1";
const maxStreamAckWorkerFailedDelta = Number(env("DEV_STRESS_MAX_STREAM_ACK_WORKER_FAILED_DELTA", "0"));
const maxStreamAckWorkerAckFailedDelta = Number(env("DEV_STRESS_MAX_STREAM_ACK_WORKER_ACK_FAILED_DELTA", "0"));
const maxStreamAckCompletionGap = Number(env("DEV_STRESS_MAX_STREAM_ACK_COMPLETION_GAP", "0"));
const streamAckDrainWaitMs = Number(env("DEV_STRESS_STREAM_ACK_DRAIN_WAIT_MS", "0"));
const streamAckDrainPollMs = Number(env("DEV_STRESS_STREAM_ACK_DRAIN_POLL_MS", "1000"));
const streamAckWorkerProbeTimeoutMs = Number(env("DEV_STRESS_STREAM_ACK_WORKER_PROBE_TIMEOUT_MS", "2000"));
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
const captureStreamDirectStats = env("DEV_STRESS_CAPTURE_STREAM_DIRECT", "0") === "1";
const failOnStreamDirectFailures =
  env("DEV_STRESS_FAIL_ON_STREAM_DIRECT_FAILURES", captureStreamDirectStats ? "1" : "0") === "1";
const maxStreamDirectFailedDelta = Number(env("DEV_STRESS_MAX_STREAM_DIRECT_FAILED_DELTA", "0"));
const maxStreamDirectNackedDelta = Number(env("DEV_STRESS_MAX_STREAM_DIRECT_NACKED_DELTA", "0"));
const maxStreamDirectTermedDelta = Number(env("DEV_STRESS_MAX_STREAM_DIRECT_TERMED_DELTA", "0"));
const maxStreamDirectUnsupportedDelta = Number(env("DEV_STRESS_MAX_STREAM_DIRECT_UNSUPPORTED_DELTA", "0"));
const maxStreamDirectCompletionGap = Number(env("DEV_STRESS_MAX_STREAM_DIRECT_COMPLETION_GAP", "0"));
const streamDirectDrainWaitMs = Number(env("DEV_STRESS_STREAM_DIRECT_DRAIN_WAIT_MS", "0"));
const streamDirectDrainPollMs = Number(env("DEV_STRESS_STREAM_DIRECT_DRAIN_POLL_MS", "1000"));
const streamDirectProbeTimeoutMs = Number(env("DEV_STRESS_STREAM_DIRECT_PROBE_TIMEOUT_MS", "2000"));
const streamDirectUrls = parseCsvStrings(
  env("DEV_STRESS_STREAM_DIRECT_URLS", env("DEV_STRESS_STREAM_DIRECT_URL", engineUrl)),
);
const captureVenueEventMaterializerStats = env("DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER", "0") === "1";
const failOnVenueEventMaterializerFailures =
  env("DEV_STRESS_FAIL_ON_VENUE_EVENT_MATERIALIZER_FAILURES", captureVenueEventMaterializerStats ? "1" : "0") === "1";
const maxVenueEventMaterializerFailedDelta = Number(env("DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_FAILED_DELTA", "0"));
const maxVenueEventMaterializerAckFailedDelta = Number(env("DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_ACK_FAILED_DELTA", "0"));
const maxVenueEventMaterializerCompletionGap = Number(env("DEV_STRESS_MAX_VENUE_EVENT_MATERIALIZER_COMPLETION_GAP", "0"));
const venueEventMaterializerDrainWaitMs = Number(env("DEV_STRESS_VENUE_EVENT_MATERIALIZER_DRAIN_WAIT_MS", "0"));
const venueEventMaterializerDrainPollMs = Number(env("DEV_STRESS_VENUE_EVENT_MATERIALIZER_DRAIN_POLL_MS", "1000"));
const venueEventMaterializerProbeTimeoutMs = Number(env("DEV_STRESS_VENUE_EVENT_MATERIALIZER_PROBE_TIMEOUT_MS", "2000"));
const venueEventMaterializerUrls = parseCsvStrings(
  env(
    "DEV_STRESS_VENUE_EVENT_MATERIALIZER_URLS",
    env("DEV_STRESS_VENUE_EVENT_MATERIALIZER_URL", defaultVenueEventMaterializerUrls(runtimeUrl).join(",")),
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
const diagnosticsSummaryOut = join(artifactDir, `${reportBaseName}-diagnostics-summary.json`);
const actionMix = resolveActionMix(profile);
const invalidIntentCodes = env(
  "DEV_STRESS_INVALID_INTENT_CODES",
  "INVALID_STATE,NOT_FOUND,VALIDATION_ERROR",
)
  .split(",")
  .map((value) => value.trim())
  .filter(Boolean);

console.log(`running stepped stress profile against ${runtimeUrl} (mode=${mode}, profile=${profile})`);

let preDiagnosticsResults = [];
let postDiagnosticsResults = [];
const telemetry = startTelemetryCapture({
  outPath: telemetryOut,
  intervalMs: telemetryIntervalMs,
  runtimeUrl,
  engineUrl,
});
if (captureDbDiagnostics) {
  resetDir(diagnosticsDir);
  preDiagnosticsResults = await captureDbDiagnosticsSnapshotsForServices("pre");
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
    postDiagnosticsResults = await captureDbDiagnosticsSnapshotsForServices("post");
    await captureDbDiagnosticsLogsForServices();
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

if (captureDbDiagnostics) {
  const diagnosticsSummary = buildDiagnosticsSummary({
    preDiagnosticsResults,
    postDiagnosticsResults,
    reportFiles,
  });
  writeFileSync(diagnosticsSummaryOut, JSON.stringify(diagnosticsSummary, null, 2));
  console.log(`  ${diagnosticsSummaryOut}`);
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

const streamAckGuardrail = evaluateStreamAckWorkerGuardrail(reportFiles);
if (!streamAckGuardrail.pass) {
  console.error("stream-ack worker guardrail failed");
  for (const failure of streamAckGuardrail.failures) {
    console.error(`  - ${failure}`);
  }
  process.exitCode = 1;
}

const streamDirectGuardrail = evaluateStreamDirectGuardrail(reportFiles);
if (!streamDirectGuardrail.pass) {
  console.error("stream-direct guardrail failed");
  for (const failure of streamDirectGuardrail.failures) {
    console.error(`  - ${failure}`);
  }
  process.exitCode = 1;
}

const venueEventMaterializerGuardrail = evaluateVenueEventMaterializerGuardrail(reportFiles);
if (!venueEventMaterializerGuardrail.pass) {
  console.error("venue-event-materializer (durable-canonical completion) guardrail failed");
  for (const failure of venueEventMaterializerGuardrail.failures) {
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

async function captureDbDiagnosticsSnapshotsForServices(stage) {
  const results = [];
  for (const service of dbDiagnosticsServices) {
    const result = await captureDbDiagnosticsSnapshot({
      diagnosticsDir: diagnosticsDirForService(service),
      stage,
      service,
      dbUser: dbDiagnosticsUser,
      dbName: dbDiagnosticsName,
      schemas: dbDiagnosticsSchemas,
    });
    results.push({ service, result });
  }
  return results;
}

async function captureDbDiagnosticsLogsForServices() {
  for (const service of dbDiagnosticsServices) {
    await captureDbDiagnosticsLogs({
      diagnosticsDir: diagnosticsDirForService(service),
      service,
      since: dbDiagnosticsLogSince,
    });
  }
}

function diagnosticsDirForService(service) {
  if (dbDiagnosticsServices.length <= 1) return diagnosticsDir;
  return join(diagnosticsDir, service);
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
  const beforeStreamDirect = captureStreamDirectStats ? await sampleStreamDirect() : null;
  const beforeVenueEventMaterializer = captureVenueEventMaterializerStats ? await sampleVenueEventMaterializer() : null;
  if (captureHotPath) {
    await resetHotPath(runtimeUrl);
  }
  try {
    await run(
      "go",
      [
        "run",
        "./cmd/load-tester",
        ...(sessionConfig ? ["--session-config", sessionConfig] : []),
        "--base-url",
        runtimeUrl,
        "--transport",
        commandTransport,
        ...(commandTransport === "stream" ? ["--stream-address", streamAddress] : []),
        "--duration",
        duration,
        "--workers",
        workers,
        "--rate",
        String(rate),
        "--rate-schedule",
        rateSchedule,
        ...(rateQueueDepth ? ["--rate-queue-depth", rateQueueDepth] : []),
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
        resolve(reportOut),
      ],
      { cwd: "services/simulator" },
    );
  } finally {
    if (captureCommandAccounting) {
      const afterAccounting = await sampleCommandAccounting(runtimeUrl, runId);
      attachCommandAccounting({ reportOut, duration, runId, beforeAccounting, afterAccounting });
    }
    if (captureStreamAckWorkerStats) {
      const afterStreamWorkers = await waitForStreamAckWorkerDrain({
        reportOut,
        beforeStreamWorkers,
        timeoutMs: streamAckDrainWaitMs,
        pollMs: streamAckDrainPollMs,
      });
      attachStreamAckWorkerStats({ reportOut, duration, beforeStreamWorkers, afterStreamWorkers });
    }    if (captureStreamDirectStats) {
      const afterStreamDirect = await waitForStreamDirectDrain({
        reportOut,
        beforeStreamDirect,
        timeoutMs: streamDirectDrainWaitMs,
        pollMs: streamDirectDrainPollMs,
      });
      attachStreamDirectStats({ reportOut, duration, beforeStreamDirect, afterStreamDirect });
    }
    if (captureVenueEventMaterializerStats) {
      const afterVenueEventMaterializer = await waitForVenueEventMaterializerDrain({
        reportOut,
        beforeVenueEventMaterializer,
        timeoutMs: venueEventMaterializerDrainWaitMs,
        pollMs: venueEventMaterializerDrainPollMs,
      });
      attachVenueEventMaterializerStats({ reportOut, duration, beforeVenueEventMaterializer, afterVenueEventMaterializer });
    }
    if (captureStreamAckProjectorStats) {
      const afterProjector = await sampleStreamAckProjectors();
      attachStreamAckProjectorStats({ reportOut, duration, beforeProjector, afterProjector });
    }
    if (captureHotPath) {
      const afterHotPath = await sampleHotPath(runtimeUrl);
      attachHotPathPhases({ reportOut, afterHotPath });
    }
    attachDerivedStressMetrics({ reportOut, duration });
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
        timeoutMs: streamAckWorkerProbeTimeoutMs,
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

async function waitForStreamAckWorkerDrain({ reportOut, beforeStreamWorkers, timeoutMs, pollMs }) {
  const started = Date.now();
  const timeout = Math.max(0, Number(timeoutMs) || 0);
  const interval = Math.max(100, Number(pollMs) || 1000);
  const expectedTerminal = streamAckExpectedTerminalCommands(reportOut);
  let latest = await sampleStreamAckWorkers();

  while (timeout > 0 && !streamAckWorkerDrainSatisfied(beforeStreamWorkers?.json, latest?.json, expectedTerminal)) {
    if (Date.now() - started >= timeout) break;
    await sleep(Math.min(interval, Math.max(0, timeout - (Date.now() - started))));
    latest = await sampleStreamAckWorkers();
  }
  return latest;
}

function streamAckExpectedTerminalCommands(reportOut) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    return Number(report.totalSuccess ?? report.totalAccepted ?? report.totalRequests ?? 0);
  } catch {
    return 0;
  }
}

function streamAckWorkerDrainSatisfied(before, after, expectedTerminal) {
  if (!after?.metrics) return true;
  const delta = streamWorkerDelta(before, after, 1);
  const terminalDelta = Number(delta?.completedDelta ?? 0) + Number(delta?.failedDelta ?? 0);
  const streamLag = (after.consumerMetrics ?? []).reduce(
    (sum, consumer) => sum + Number(consumer.streamLag ?? consumer.pending ?? 0),
    0,
  );
  if (expectedTerminal > 0 && terminalDelta < expectedTerminal) return false;
  return streamLag <= 0;
}

async function sampleStreamDirect() {
  const probes = await Promise.all(
    streamDirectUrls.map((baseUrl, index) =>
      requestAppProbe({
        name: `streamDirect.${index}.stats`,
        url: `${baseUrl}/internal/stream-direct/stats`,
        captureJson: true,
        timeoutMs: streamDirectProbeTimeoutMs,
      }),
    ),
  );
  return {
    ok: probes.every((probe) => probe.ok),
    status: probes.find((probe) => !probe.ok)?.status ?? 200,
    latencyMs: Math.max(0, ...probes.map((probe) => Number(probe.latencyMs ?? 0))),
    error: probes.find((probe) => probe.error)?.error ?? "",
    probes,
    json: aggregateStreamDirectStats(probes),
  };
}

async function waitForStreamDirectDrain({ reportOut, beforeStreamDirect, timeoutMs, pollMs }) {
  const started = Date.now();
  const timeout = Math.max(0, Number(timeoutMs) || 0);
  const interval = Math.max(100, Number(pollMs) || 1000);
  const expectedTerminal = streamAckExpectedTerminalCommands(reportOut);
  let latest = await sampleStreamDirect();

  while (timeout > 0 && !streamDirectDrainSatisfied(beforeStreamDirect?.json, latest?.json, expectedTerminal)) {
    if (Date.now() - started >= timeout) break;
    await sleep(Math.min(interval, Math.max(0, timeout - (Date.now() - started))));
    latest = await sampleStreamDirect();
  }
  return latest;
}

function streamDirectDrainSatisfied(before, after, expectedTerminal) {
  if (!after?.metrics) return expectedTerminal <= 0;
  const delta = streamDirectDelta(before, after, 1);
  const terminalDelta =
    Number(delta?.ackedDelta ?? 0) +
    Number(delta?.failedDelta ?? 0) +
    Number(delta?.nackedDelta ?? 0) +
    Number(delta?.termedDelta ?? 0);
  if (expectedTerminal > 0 && terminalDelta < expectedTerminal) return false;
  return true;
}

async function sampleVenueEventMaterializer() {
  const probes = await Promise.all(
    venueEventMaterializerUrls.map((baseUrl, index) =>
      requestAppProbe({
        name: `venueEventMaterializer.${index}.stats`,
        url: `${baseUrl}/internal/venue-event-materializer/stats`,
        captureJson: true,
        timeoutMs: venueEventMaterializerProbeTimeoutMs,
      }),
    ),
  );
  return {
    ok: probes.every((probe) => probe.ok),
    status: probes.find((probe) => !probe.ok)?.status ?? 200,
    latencyMs: Math.max(0, ...probes.map((probe) => Number(probe.latencyMs ?? 0))),
    error: probes.find((probe) => probe.error)?.error ?? "",
    probes,
    json: aggregateVenueEventMaterializerStats(probes),
  };
}

function aggregateVenueEventMaterializerStats(probes) {
  const instances = probes
    .map((probe, index) => ({ index, url: venueEventMaterializerUrls[index], stats: probe.json }))
    .filter((instance) => instance.stats);
  const metrics = instances.reduce(
    (totals, instance) => {
      const m = instance.stats.metrics ?? {};
      totals.fetched += Number(m.fetched ?? 0);
      totals.materialized += Number(m.materialized ?? 0);
      totals.materializedOutcomes += Number(m.materializedOutcomes ?? 0);
      totals.failed += Number(m.failed ?? 0);
      totals.ackFailed += Number(m.ackFailed ?? 0);
      totals.unsupported += Number(m.unsupported ?? 0);
      return totals;
    },
    { fetched: 0, materialized: 0, materializedOutcomes: 0, failed: 0, ackFailed: 0, unsupported: 0 },
  );
  return { instances, metrics };
}

async function waitForVenueEventMaterializerDrain({ reportOut, beforeVenueEventMaterializer, timeoutMs, pollMs }) {
  const started = Date.now();
  const timeout = Math.max(0, Number(timeoutMs) || 0);
  const interval = Math.max(100, Number(pollMs) || 1000);
  const expectedTerminal = streamAckExpectedTerminalCommands(reportOut);
  let latest = await sampleVenueEventMaterializer();

  while (timeout > 0 && !venueEventMaterializerDrainSatisfied(beforeVenueEventMaterializer?.json, latest?.json, expectedTerminal)) {
    if (Date.now() - started >= timeout) break;
    await sleep(Math.min(interval, Math.max(0, timeout - (Date.now() - started))));
    latest = await sampleVenueEventMaterializer();
  }
  return latest;
}

function venueEventMaterializerDrainSatisfied(before, after, expectedTerminal) {
  if (!after?.metrics) return expectedTerminal <= 0;
  const delta = venueEventMaterializerDelta(before, after, 1);
  const terminalDelta = Number(delta?.materializedDelta ?? 0) + Number(delta?.failedDelta ?? 0);
  if (expectedTerminal > 0 && terminalDelta < expectedTerminal) return false;
  return true;
}

async function resetHotPath(runtimeUrl) {
  return requestAppProbe({
    name: "runtime.hotPath.reset",
    url: `${runtimeUrl}/internal/perf/hot-path`,
    method: "POST",
    captureJson: true,
  });
}

async function sampleHotPath(runtimeUrl) {
  return requestAppProbe({
    name: "runtime.hotPath",
    url: `${runtimeUrl}/internal/perf/hot-path`,
    captureJson: true,
  });
}

function attachHotPathPhases({ reportOut, afterHotPath }) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    const phases = afterHotPath?.json?.metrics?.phases ?? {};
    const sortedPhases = Object.fromEntries(
      Object.entries(phases).sort(([left], [right]) => left.localeCompare(right)),
    );
    const streamAckPhases = Object.fromEntries(
      Object.entries(phases)
        .filter(([name]) => name.startsWith("api.streamAck.") || name.startsWith("streamWorker."))
        .sort(([left], [right]) => left.localeCompare(right)),
    );
    report.hotPathPhases = {
      phases: sortedPhases,
      probe: accountingProbeSummary(afterHotPath),
    };
    report.streamAckApiPhases = {
      phases: streamAckPhases,
      probe: accountingProbeSummary(afterHotPath),
    };
    writeFileSync(reportOut, JSON.stringify(report, null, 2));

    const total = streamAckPhases["api.streamAck.total"];
    const reserve = streamAckPhases["api.streamAck.reserve"];
    const publishAck = streamAckPhases["api.streamAck.publishAck"];
    const markPublished = streamAckPhases["api.streamAck.markPublished"];
    const markPublishedBatch = streamAckPhases["streamWorker.markPublishedBatch"];
    const enqueuePublished = streamAckPhases["api.streamAck.enqueuePublished"];
    const asyncFlush = streamAckPhases["api.streamAck.markPublished.asyncFlush"];
    const pipelineQueueWait = streamAckPhases["api.streamAck.publishPipeline.queueWait"];
    const pipelineSlotWait = streamAckPhases["api.streamAck.publishPipeline.slotWait"];
    const pipelineDelegateAck = streamAckPhases["api.streamAck.publishPipeline.delegateAck"];
    const pipelineTotal = streamAckPhases["api.streamAck.publishPipeline.total"];
    if (total || reserve || publishAck || markPublished || markPublishedBatch || enqueuePublished) {
      console.log(
        `  stream-ack-api totalAvg=${formatPhaseAvg(total)} reserveAvg=${formatPhaseAvg(reserve)} publishAckAvg=${formatPhaseAvg(publishAck)} markPublishedAvg=${formatPhaseAvg(markPublished)} workerMarkPublishedBatchAvg=${formatPhaseAvg(markPublishedBatch)} enqueuePublishedAvg=${formatPhaseAvg(enqueuePublished)} asyncFlushAvg=${formatPhaseAvg(asyncFlush)}`,
      );
    }
    if (pipelineQueueWait || pipelineSlotWait || pipelineDelegateAck || pipelineTotal) {
      console.log(
        `  stream-ack-publish-pipeline queueWaitAvg=${formatPhaseAvg(pipelineQueueWait)} slotWaitAvg=${formatPhaseAvg(pipelineSlotWait)} delegateAckAvg=${formatPhaseAvg(pipelineDelegateAck)} totalAvg=${formatPhaseAvg(pipelineTotal)}`,
      );
    }
    const mutationTotal = phases["api.mutation.total"];
    const operation = phases["api.operation"];
    const parseSubmit = phases["api.parse.submitOrder"];
    const runtimeTotal = phases["runtime.submitOrder.total"];
    const engineSubmit = phases["runtime.engine.submit"];
    const persistence = phases["runtime.persistence.persistSubmitOutcome"];
    const serialize = phases["api.response.serializeSubmitOrder"];
    const writeResponse = phases["api.writeResponse"];
    if (mutationTotal || operation || runtimeTotal || engineSubmit) {
      console.log(
        `  sync-hot-path totalAvg=${formatPhaseAvg(mutationTotal)} operationAvg=${formatPhaseAvg(operation)} parseAvg=${formatPhaseAvg(parseSubmit)} runtimeAvg=${formatPhaseAvg(runtimeTotal)} engineAvg=${formatPhaseAvg(engineSubmit)} persistAvg=${formatPhaseAvg(persistence)} serializeAvg=${formatPhaseAvg(serialize)} writeAvg=${formatPhaseAvg(writeResponse)}`,
      );
    }
  } catch (error) {
    console.warn(`  hot-path phases unavailable: ${error?.message ?? error}`);
  }
}

function formatPhaseAvg(phase) {
  if (!phase) return "n/a";
  return `${Number(phase.avgMs ?? 0).toFixed(2)}ms`;
}

function defaultStreamAckWorkerUrls(runtimeUrl) {
  const parsed = new URL(runtimeUrl);
  const worker0Port = env("REEF_PLATFORM_WORKER_0_HOST_PORT", "8082");
  const worker1Port = env("REEF_PLATFORM_WORKER_1_HOST_PORT", "8083");
  const worker2Port = env("REEF_PLATFORM_WORKER_2_HOST_PORT", "8086");
  const worker3Port = env("REEF_PLATFORM_WORKER_3_HOST_PORT", "8087");
  return [
    `${parsed.protocol}//${parsed.hostname}:${worker0Port}`,
    `${parsed.protocol}//${parsed.hostname}:${worker1Port}`,
    `${parsed.protocol}//${parsed.hostname}:${worker2Port}`,
    `${parsed.protocol}//${parsed.hostname}:${worker3Port}`,
  ];
}

function defaultVenueEventMaterializerUrls(runtimeUrl) {
  const parsed = new URL(runtimeUrl);
  const materializerPort = env("REEF_PLATFORM_MATERIALIZER_HOST_PORT", "8091");
  return [`${parsed.protocol}//${parsed.hostname}:${materializerPort}`];
}

function defaultStreamAckProjectorUrls(runtimeUrl) {
  const parsed = new URL(runtimeUrl);
  const projector0Port = env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", env("REEF_PLATFORM_PROJECTOR_HOST_PORT", "8084"));
  const projector1Port = env("REEF_PLATFORM_PROJECTOR_1_HOST_PORT", "8085");
  const projector2Port = env("REEF_PLATFORM_PROJECTOR_2_HOST_PORT", "8088");
  const projector3Port = env("REEF_PLATFORM_PROJECTOR_3_HOST_PORT", "8089");
  return [
    `${parsed.protocol}//${parsed.hostname}:${projector0Port}`,
    `${parsed.protocol}//${parsed.hostname}:${projector1Port}`,
    `${parsed.protocol}//${parsed.hostname}:${projector2Port}`,
    `${parsed.protocol}//${parsed.hostname}:${projector3Port}`,
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

function aggregateStreamDirectStats(probes) {
  const engineStats = probes
    .map((probe, index) => ({ index, url: streamDirectUrls[index], stats: probe.json }))
    .filter((engine) => engine.stats);
  const partitionMetrics = mergeStreamDirectPartitions(
    engineStats.flatMap((engine) =>
      (engine.stats.partitions ?? []).map((partition) => ({
        ...partition,
        engineIndex: engine.index,
        engineUrl: engine.url,
      })),
    ),
  );
  const metrics = sumStreamDirectMetrics(partitionMetrics);
  return {
    enabled: engineStats.some((engine) => engine.stats.enabled === true),
    engines: engineStats.map((engine) => ({
      index: engine.index,
      url: engine.url,
      enabled: engine.stats.enabled,
      partitionCount: Array.isArray(engine.stats.partitions) ? engine.stats.partitions.length : 0,
    })),
    partitions: partitionMetrics.map((partition) => Number(partition.partition)).sort((a, b) => a - b),
    metrics,
    partitionMetrics,
  };
}

function mergeStreamDirectPartitions(partitions) {
  const byPartition = new Map();
  for (const raw of partitions) {
    const partition = Number(raw.partition);
    if (!Number.isFinite(partition)) continue;
    const current = byPartition.get(partition) ?? {
      partition,
      shardId: raw.shardId ?? "",
      engineIndex: raw.engineIndex,
      engineUrl: raw.engineUrl,
      fetched: 0,
      processed: 0,
      published: 0,
      acked: 0,
      nacked: 0,
      termed: 0,
      failed: 0,
      unsupported: 0,
      lastFetchedAt: "",
      lastAckedAt: "",
      lastError: "",
    };
    current.fetched += Number(raw.fetched ?? 0);
    current.processed += Number(raw.processed ?? 0);
    current.published += Number(raw.published ?? 0);
    current.acked += Number(raw.acked ?? 0);
    current.nacked += Number(raw.nacked ?? 0);
    current.termed += Number(raw.termed ?? 0);
    current.failed += Number(raw.failed ?? 0);
    current.unsupported += Number(raw.unsupported ?? 0);
    current.lastFetchedAt = maxIso(current.lastFetchedAt, raw.lastFetchedAt ?? "");
    current.lastAckedAt = maxIso(current.lastAckedAt, raw.lastAckedAt ?? "");
    current.lastError = raw.lastError || current.lastError;
    byPartition.set(partition, current);
  }
  return [...byPartition.values()].sort((a, b) => a.partition - b.partition);
}

function sumStreamDirectMetrics(partitions) {
  const totals = {
    fetched: 0,
    processed: 0,
    published: 0,
    acked: 0,
    nacked: 0,
    termed: 0,
    failed: 0,
    unsupported: 0,
    lastFetchedAt: "",
    lastAckedAt: "",
    lastError: "",
  };
  for (const partition of partitions) {
    totals.fetched += Number(partition.fetched ?? 0);
    totals.processed += Number(partition.processed ?? 0);
    totals.published += Number(partition.published ?? 0);
    totals.acked += Number(partition.acked ?? 0);
    totals.nacked += Number(partition.nacked ?? 0);
    totals.termed += Number(partition.termed ?? 0);
    totals.failed += Number(partition.failed ?? 0);
    totals.unsupported += Number(partition.unsupported ?? 0);
    totals.lastFetchedAt = maxIso(totals.lastFetchedAt, partition.lastFetchedAt ?? "");
    totals.lastAckedAt = maxIso(totals.lastAckedAt, partition.lastAckedAt ?? "");
    totals.lastError = partition.lastError || totals.lastError;
  }
  return totals;
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

function attachStreamDirectStats({ reportOut, duration, beforeStreamDirect, afterStreamDirect }) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    const before = beforeStreamDirect?.json ?? null;
    const after = afterStreamDirect?.json ?? null;
    const durationSeconds = Number(report.durationSeconds ?? 0) || parseDurationSeconds(duration);
    const delta = streamDirectDelta(before, after, durationSeconds);
    report.streamDirect = {
      before,
      after,
      delta,
      probes: {
        before: accountingProbeSummary(beforeStreamDirect),
        after: accountingProbeSummary(afterStreamDirect),
      },
    };
    writeFileSync(reportOut, JSON.stringify(report, null, 2));
    if (delta) {
      const hotPartitions = delta.partitionDeltas
        .slice()
        .sort((a, b) => b.ackedDelta - a.ackedDelta)
        .slice(0, 4)
        .map((partition) => `p${partition.partition}:${partition.ackedDelta}`)
        .join(" ");
      console.log(
        `  stream-direct fetchedDelta=${delta.fetchedDelta} processedDelta=${delta.processedDelta} publishedDelta=${delta.publishedDelta} ackedDelta=${delta.ackedDelta} failedDelta=${delta.failedDelta} nackedDelta=${delta.nackedDelta} ackedRps=${delta.ackedRps.toFixed(2)} hotPartitions=${hotPartitions}`,
      );
    }
  } catch (error) {
    console.warn(`  stream-direct stats unavailable: ${error?.message ?? error}`);
  }
}

function attachVenueEventMaterializerStats({ reportOut, duration, beforeVenueEventMaterializer, afterVenueEventMaterializer }) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    const before = beforeVenueEventMaterializer?.json ?? null;
    const after = afterVenueEventMaterializer?.json ?? null;
    const durationSeconds = Number(report.durationSeconds ?? 0) || parseDurationSeconds(duration);
    const delta = venueEventMaterializerDelta(before, after, durationSeconds);
    report.venueEventMaterializer = {
      before,
      after,
      delta,
      probes: {
        before: accountingProbeSummary(beforeVenueEventMaterializer),
        after: accountingProbeSummary(afterVenueEventMaterializer),
      },
    };
    writeFileSync(reportOut, JSON.stringify(report, null, 2));
    if (delta) {
      console.log(
        `  venue-event-materializer fetchedDelta=${delta.fetchedDelta} materializedDelta=${delta.materializedDelta} materializedBatchesDelta=${delta.materializedBatchesDelta} failedDelta=${delta.failedDelta} ackFailedDelta=${delta.ackFailedDelta} materializedRps=${delta.materializedRps.toFixed(2)}`,
      );
    }
  } catch (error) {
    console.warn(`  venue-event-materializer stats unavailable: ${error?.message ?? error}`);
  }
}

function venueEventMaterializerDelta(before, after, durationSeconds) {
  if (!after?.metrics) return null;
  const beforeMetrics = before?.metrics ?? {
    fetched: 0,
    materialized: 0,
    materializedOutcomes: 0,
    failed: 0,
    ackFailed: 0,
    unsupported: 0,
  };
  const afterMetrics = after.metrics;
  // materializedDelta is command outcomes durably committed (the gate metric); materializedBatchesDelta
  // is the raw batch-ack count, kept only as a diagnostic since one batch can carry many commands.
  const materializedDelta = Number(afterMetrics.materializedOutcomes ?? 0) - Number(beforeMetrics.materializedOutcomes ?? 0);
  const materializedBatchesDelta = Number(afterMetrics.materialized ?? 0) - Number(beforeMetrics.materialized ?? 0);
  return {
    fetchedDelta: Number(afterMetrics.fetched ?? 0) - Number(beforeMetrics.fetched ?? 0),
    materializedDelta,
    materializedBatchesDelta,
    failedDelta: Number(afterMetrics.failed ?? 0) - Number(beforeMetrics.failed ?? 0),
    ackFailedDelta: Number(afterMetrics.ackFailed ?? 0) - Number(beforeMetrics.ackFailed ?? 0),
    unsupportedDelta: Number(afterMetrics.unsupported ?? 0) - Number(beforeMetrics.unsupported ?? 0),
    materializedRps: durationSeconds > 0 ? materializedDelta / durationSeconds : 0,
  };
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

function attachDerivedStressMetrics({ reportOut, duration }) {
  try {
    const report = JSON.parse(readFileSync(reportOut, "utf8"));
    const durationSeconds = Number(report.durationSeconds ?? 0) || parseDurationSeconds(duration);
    const attemptedCommands = Number(report.totalRequests ?? 0);
    const acceptedCommands = Number(report.totalSuccess ?? 0);
    const workerCompletedCommands = Number(report.streamAckWorkers?.delta?.completedDelta ?? 0);
    const directAckedCommands = Number(report.streamDirect?.delta?.ackedDelta ?? 0);
    const directPublishedOutcomes = Number(report.streamDirect?.delta?.publishedDelta ?? 0);
    const projectedWorkItems = Number(report.streamAckProjector?.delta?.projectedDelta ?? 0);
    const durableCanonicalCompletedItems = Number(report.venueEventMaterializer?.delta?.materializedDelta ?? 0);
    report.unitMetrics = {
      units: {
        attemptedCommands: "commands submitted by the load generator",
        acceptedCommands: "commands durably accepted by the API with 202",
        workerCompletedCommands: "commands completed by stream-ack workers after canonical persistence",
        directAckedCommands: "commands consumed by matching-engine direct stream workers and acked after venue event batch publish (engine ack, not durable canonical commit)",
        directPublishedOutcomes: "command outcomes published by matching-engine direct stream workers to the venue event stream",
        durableCanonicalCompletedItems: "command outcomes durably committed to compact canonical Postgres rows by the venue event batch materializer; this is the primary completed/sec metric for durable-canonical gates",
        projectedWorkItems: "projection work items applied by stream-ack projectors",
        projectionLag: "projector backlog in projection work items / partition sequence units",
      },
      durationSeconds,
      attemptedCommands,
      acceptedCommands,
      workerCompletedCommands,
      directAckedCommands,
      directPublishedOutcomes,
      durableCanonicalCompletedItems,
      projectedWorkItems,
      attemptedCommandsPerSecond: perSecond(attemptedCommands, durationSeconds),
      acceptedCommandsPerSecond: perSecond(acceptedCommands, durationSeconds),
      workerCompletedCommandsPerSecond: perSecond(workerCompletedCommands, durationSeconds),
      directAckedCommandsPerSecond: perSecond(directAckedCommands, durationSeconds),
      directPublishedOutcomesPerSecond: perSecond(directPublishedOutcomes, durationSeconds),
      durableCanonicalCompletedPerSecond: perSecond(durableCanonicalCompletedItems, durationSeconds),
      projectedWorkItemsPerSecond: perSecond(projectedWorkItems, durationSeconds),
      completedToAcceptedRatio: ratio(workerCompletedCommands, acceptedCommands),
      directAckedToAcceptedRatio: ratio(directAckedCommands, acceptedCommands),
      directPublishedToAcceptedRatio: ratio(directPublishedOutcomes, acceptedCommands),
      durableCanonicalCompletedToAcceptedRatio: ratio(durableCanonicalCompletedItems, acceptedCommands),
      durableCanonicalCompletionGap: Math.max(acceptedCommands - durableCanonicalCompletedItems, 0),
      projectedToCompletedRatio: ratio(projectedWorkItems, workerCompletedCommands),
      projectionLagAfter: Number(report.streamAckProjector?.delta?.afterLag ?? 0),
    };
    report.partitionSkew = buildPartitionSkew(report.streamAckWorkers?.delta);
    report.streamDirectPartitionSkew = buildStreamDirectPartitionSkew(report.streamDirect?.delta);
    writeFileSync(reportOut, JSON.stringify(report, null, 2));
  } catch (error) {
    console.warn(`  derived stress metrics unavailable: ${error?.message ?? error}`);
  }
}

function buildStreamDirectPartitionSkew(directDelta) {
  const partitionDeltas = directDelta?.partitionDeltas ?? [];
  const partitions = partitionDeltas.map((partitionDelta) => ({
    partition: Number(partitionDelta.partition),
    fetchedCommands: Number(partitionDelta.fetchedDelta ?? 0),
    processedCommands: Number(partitionDelta.processedDelta ?? 0),
    publishedOutcomes: Number(partitionDelta.publishedDelta ?? 0),
    ackedCommands: Number(partitionDelta.ackedDelta ?? 0),
    nackedCommands: Number(partitionDelta.nackedDelta ?? 0),
    failedCommands: Number(partitionDelta.failedDelta ?? 0),
    unsupportedCommands: Number(partitionDelta.unsupportedDelta ?? 0),
  }));
  const ackedValues = partitions.map((partition) => partition.ackedCommands);
  const positiveAcked = ackedValues.filter((value) => value > 0);
  const maxAcked = ackedValues.length ? Math.max(...ackedValues) : 0;
  const minPositiveAcked = positiveAcked.length ? Math.min(...positiveAcked) : 0;
  const totalAcked = ackedValues.reduce((sum, value) => sum + value, 0);
  return {
    units: {
      fetchedCommands: "commands fetched by matching-engine direct partition consumer",
      processedCommands: "commands applied to the matching-engine service",
      publishedOutcomes: "command outcomes published in venue event batches",
      ackedCommands: "commands acked after venue event batch publication",
    },
    partitionCount: partitions.length,
    activePartitions: positiveAcked.length,
    ackedCommands: {
      total: totalAcked,
      average: partitions.length > 0 ? totalAcked / partitions.length : 0,
      max: maxAcked,
      minPositive: minPositiveAcked,
      skewRatio: minPositiveAcked > 0 ? maxAcked / minPositiveAcked : null,
    },
    topByAckedCommands: partitions
      .slice()
      .sort((a, b) => b.ackedCommands - a.ackedCommands)
      .slice(0, 8),
    topByFailures: partitions
      .slice()
      .sort(
        (a, b) =>
          b.failedCommands + b.nackedCommands + b.unsupportedCommands -
          (a.failedCommands + a.nackedCommands + a.unsupportedCommands),
      )
      .slice(0, 8),
    partitions,
  };
}

function buildPartitionSkew(workerDelta) {
  const partitionDeltas = workerDelta?.partitionDeltas ?? [];
  const consumerByPartition = new Map(
    (workerDelta?.consumerMetrics ?? [])
      .map((consumer) => [Number(consumer.partition), consumer])
      .filter(([partition]) => Number.isFinite(partition)),
  );
  const partitions = partitionDeltas.map((partitionDelta) => {
    const partition = Number(partitionDelta.partition);
    const consumer = consumerByPartition.get(partition) ?? {};
    return {
      partition,
      fetchedCommands: Number(partitionDelta.fetchedDelta ?? 0),
      completedCommands: Number(partitionDelta.completedDelta ?? 0),
      failedCommands: Number(partitionDelta.failedDelta ?? 0),
      ackFailedCommands: Number(partitionDelta.ackFailedDelta ?? 0),
      localInFlightAfter: Number(partitionDelta.localInFlightAfter ?? consumer.localInFlight ?? 0),
      streamLagAfter: Number(consumer.streamLag ?? 0),
      ackPendingAfter: Number(consumer.ackPending ?? consumer.numAckPending ?? 0),
      maxDeliveredCount: Number(partitionDelta.maxDeliveredCount ?? consumer.maxDeliveredCount ?? 0),
    };
  });
  const completedValues = partitions.map((partition) => partition.completedCommands);
  const positiveCompleted = completedValues.filter((value) => value > 0);
  const maxCompleted = completedValues.length ? Math.max(...completedValues) : 0;
  const minPositiveCompleted = positiveCompleted.length ? Math.min(...positiveCompleted) : 0;
  const totalCompleted = completedValues.reduce((sum, value) => sum + value, 0);
  return {
    units: {
      fetchedCommands: "commands fetched by worker partition",
      completedCommands: "commands completed by worker partition",
      streamLagAfter: "JetStream stream sequence lag after the step",
      ackPendingAfter: "consumer ack-pending messages after the step",
    },
    partitionCount: partitions.length,
    activePartitions: positiveCompleted.length,
    completedCommands: {
      total: totalCompleted,
      average: partitions.length > 0 ? totalCompleted / partitions.length : 0,
      max: maxCompleted,
      minPositive: minPositiveCompleted,
      skewRatio: minPositiveCompleted > 0 ? maxCompleted / minPositiveCompleted : null,
    },
    topByCompletedCommands: partitions
      .slice()
      .sort((a, b) => b.completedCommands - a.completedCommands)
      .slice(0, 8),
    topByStreamLag: partitions
      .slice()
      .sort((a, b) => b.streamLagAfter - a.streamLagAfter)
      .slice(0, 8),
    partitions,
  };
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

function streamDirectDelta(before, after, durationSeconds) {
  const beforeMetrics = before?.metrics;
  const afterMetrics = after?.metrics;
  if (!beforeMetrics || !afterMetrics) return null;
  const fetchedDelta = Number(afterMetrics.fetched ?? 0) - Number(beforeMetrics.fetched ?? 0);
  const processedDelta = Number(afterMetrics.processed ?? 0) - Number(beforeMetrics.processed ?? 0);
  const publishedDelta = Number(afterMetrics.published ?? 0) - Number(beforeMetrics.published ?? 0);
  const ackedDelta = Number(afterMetrics.acked ?? 0) - Number(beforeMetrics.acked ?? 0);
  const nackedDelta = Number(afterMetrics.nacked ?? 0) - Number(beforeMetrics.nacked ?? 0);
  const termedDelta = Number(afterMetrics.termed ?? 0) - Number(beforeMetrics.termed ?? 0);
  const failedDelta = Number(afterMetrics.failed ?? 0) - Number(beforeMetrics.failed ?? 0);
  const unsupportedDelta = Number(afterMetrics.unsupported ?? 0) - Number(beforeMetrics.unsupported ?? 0);
  const partitionDeltas = streamDirectPartitionDeltas(before, after, durationSeconds);
  return {
    fetchedDelta,
    processedDelta,
    publishedDelta,
    ackedDelta,
    nackedDelta,
    termedDelta,
    failedDelta,
    unsupportedDelta,
    fetchedRps: durationSeconds > 0 ? fetchedDelta / durationSeconds : 0,
    processedRps: durationSeconds > 0 ? processedDelta / durationSeconds : 0,
    publishedRps: durationSeconds > 0 ? publishedDelta / durationSeconds : 0,
    ackedRps: durationSeconds > 0 ? ackedDelta / durationSeconds : 0,
    partitionDeltas,
  };
}

function streamDirectPartitionDeltas(before, after, durationSeconds) {
  const beforeByPartition = new Map(
    (before?.partitionMetrics ?? []).map((partition) => [Number(partition.partition), partition]),
  );
  return (after?.partitionMetrics ?? [])
    .map((afterPartition) => {
      const partition = Number(afterPartition.partition);
      const beforePartition = beforeByPartition.get(partition) ?? {};
      const fetchedDelta = Number(afterPartition.fetched ?? 0) - Number(beforePartition.fetched ?? 0);
      const processedDelta = Number(afterPartition.processed ?? 0) - Number(beforePartition.processed ?? 0);
      const publishedDelta = Number(afterPartition.published ?? 0) - Number(beforePartition.published ?? 0);
      const ackedDelta = Number(afterPartition.acked ?? 0) - Number(beforePartition.acked ?? 0);
      const nackedDelta = Number(afterPartition.nacked ?? 0) - Number(beforePartition.nacked ?? 0);
      const termedDelta = Number(afterPartition.termed ?? 0) - Number(beforePartition.termed ?? 0);
      const failedDelta = Number(afterPartition.failed ?? 0) - Number(beforePartition.failed ?? 0);
      const unsupportedDelta = Number(afterPartition.unsupported ?? 0) - Number(beforePartition.unsupported ?? 0);
      return {
        partition,
        shardId: afterPartition.shardId ?? "",
        fetchedDelta,
        processedDelta,
        publishedDelta,
        ackedDelta,
        nackedDelta,
        termedDelta,
        failedDelta,
        unsupportedDelta,
        fetchedRps: durationSeconds > 0 ? fetchedDelta / durationSeconds : 0,
        processedRps: durationSeconds > 0 ? processedDelta / durationSeconds : 0,
        publishedRps: durationSeconds > 0 ? publishedDelta / durationSeconds : 0,
        ackedRps: durationSeconds > 0 ? ackedDelta / durationSeconds : 0,
      };
    })
    .sort((a, b) => a.partition - b.partition);
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

function perSecond(count, durationSeconds) {
  return durationSeconds > 0 ? count / durationSeconds : 0;
}

function ratio(numerator, denominator) {
  return denominator > 0 ? numerator / denominator : null;
}

function buildDiagnosticsSummary({ preDiagnosticsResults, postDiagnosticsResults, reportFiles }) {
  const reportTotals = readReportTotals(reportFiles);
  const services = {};
  for (const { service, result: preResult } of preDiagnosticsResults) {
    const postResult = postDiagnosticsResults.find((entry) => entry.service === service)?.result ?? null;
    services[service] = buildDiagnosticsServiceSummary({
      preResult,
      postResult,
      reportTotals,
    });
  }
  return {
    generatedAt: new Date().toISOString(),
    ok: Object.values(services).every((serviceSummary) => serviceSummary.ok),
    reportTotals,
    services,
  };
}

function buildDiagnosticsServiceSummary({ preResult, postResult, reportTotals }) {
  const delta = summarizeDiagnosticsDelta(preResult, postResult);
  if (!delta.ok) {
    return {
      ok: false,
      reason: delta.reason,
    };
  }
  const canonicalEvents = tableMetric(delta.tables, "runtime.canonical_venue_events", "insertsDelta");
  const canonicalCommandResults = tableMetric(delta.tables, "runtime.canonical_command_results", "insertsDelta");
  return {
    ok: true,
    units: {
      walBytes: "PostgreSQL WAL bytes from pg_stat_wal during the captured window",
      commits: "database commits from pg_stat_database during the captured window",
      canonicalEvents: "rows inserted into runtime.canonical_venue_events",
      canonicalCommandResults: "rows inserted into runtime.canonical_command_results",
      workerCompletedCommands: "commands completed by stream-ack workers in measured reports",
    },
    unitMetrics: {
      canonicalEvents,
      canonicalCommandResults,
      walBytes: Number(delta.wal?.walBytes ?? 0),
      commits: Number(delta.database?.xactCommit ?? 0),
      tuplesInserted: Number(delta.database?.tuplesInserted ?? 0),
      tuplesUpdated: Number(delta.database?.tuplesUpdated ?? 0),
      canonicalEventsPerAcceptedCommand: ratio(canonicalEvents, reportTotals.acceptedCommands),
      canonicalEventsPerWorkerCompletedCommand: ratio(canonicalEvents, reportTotals.workerCompletedCommands),
      commandResultsPerAcceptedCommand: ratio(canonicalCommandResults, reportTotals.acceptedCommands),
      commandResultsPerWorkerCompletedCommand: ratio(canonicalCommandResults, reportTotals.workerCompletedCommands),
      walBytesPerAcceptedCommand: ratio(Number(delta.wal?.walBytes ?? 0), reportTotals.acceptedCommands),
      walBytesPerWorkerCompletedCommand: ratio(Number(delta.wal?.walBytes ?? 0), reportTotals.workerCompletedCommands),
      commitsPerAcceptedCommand: ratio(Number(delta.database?.xactCommit ?? 0), reportTotals.acceptedCommands),
      commitsPerWorkerCompletedCommand: ratio(Number(delta.database?.xactCommit ?? 0), reportTotals.workerCompletedCommands),
    },
    wal: delta.wal,
    database: delta.database,
    topTablesByBytes: delta.tables.slice(0, 20),
    topTablesByInserts: delta.tables
      .slice()
      .sort((a, b) => Math.abs(Number(b.insertsDelta ?? 0)) - Math.abs(Number(a.insertsDelta ?? 0)))
      .slice(0, 20),
  };
}

function readReportTotals(reportFiles) {
  const totals = {
    reportCount: 0,
    attemptedCommands: 0,
    acceptedCommands: 0,
    workerCompletedCommands: 0,
    directAckedCommands: 0,
    directPublishedOutcomes: 0,
    durableCanonicalCompletedItems: 0,
    projectedWorkItems: 0,
  };
  for (const path of reportFiles) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      totals.reportCount += 1;
      totals.attemptedCommands += Number(report.totalRequests ?? 0);
      totals.acceptedCommands += Number(report.totalSuccess ?? 0);
      totals.workerCompletedCommands += Number(report.streamAckWorkers?.delta?.completedDelta ?? 0);
      totals.directAckedCommands += Number(report.streamDirect?.delta?.ackedDelta ?? 0);
      totals.directPublishedOutcomes += Number(report.streamDirect?.delta?.publishedDelta ?? 0);
      totals.durableCanonicalCompletedItems += Number(report.venueEventMaterializer?.delta?.materializedDelta ?? 0);
      totals.projectedWorkItems += Number(report.streamAckProjector?.delta?.projectedDelta ?? 0);
    } catch {
      // skip unreadable report
    }
  }
  return totals;
}

function tableMetric(tables, tableName, metricName) {
  const match = (tables ?? []).find((table) => table.table === tableName);
  return Number(match?.[metricName] ?? 0);
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
    { name: "engine.streamDirect", url: `${engineUrl}/internal/stream-direct/stats`, captureJson: true },
    { name: "engine.metrics", url: `${engineUrl}/actuator/prometheus` },
    ...venueEventMaterializerUrls.map((baseUrl, index) => ({
      name: `venueEventMaterializer.${index}.stats`,
      url: `${baseUrl}/internal/venue-event-materializer/stats`,
      captureJson: true,
    })),
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
    const req = client.request(
      url,
      { method: probe.method ?? "GET", timeout: Math.max(1, Number(probe.timeoutMs ?? 2000)) },
      (response) => {
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
      const streamAckIssues = streamAckWorkerIssues(report);
      const directIssues = streamDirectIssues(report);
      const streamIssueCount = streamAckIssues.totalIssueCount + directIssues.totalIssueCount;
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
        streamAckClean: streamIssueCount === 0,
        streamAckIssueCount: streamIssueCount,
        streamDirectClean: directIssues.totalIssueCount === 0,
        streamDirectIssueCount: directIssues.totalIssueCount,
      });
    } catch {
      // skip unreadable report
    }
  }
  if (rows.length === 0) return null;
  const latencyTargetMs = 100;
  const clean = rows.filter((row) => row.streamAckClean);
  const acceptableClean = clean.filter((row) => row.p95Ms <= latencyTargetMs && row.p99Ms <= latencyTargetMs * 1.5);
  const acceptable = rows.filter((row) => row.p95Ms <= latencyTargetMs && row.p99Ms <= latencyTargetMs * 1.5);
  const candidates = acceptableClean.length > 0 ? acceptableClean : clean.length > 0 ? clean : acceptable.length > 0 ? acceptable : rows;
  const scored = candidates.map((row) => ({
    ...row,
    score:
      row.acceptedRps -
      Math.max(0, row.p95Ms-latencyTargetMs)*0.75 -
      Math.max(0, row.p99Ms-latencyTargetMs*1.5)*0.5 -
      row.streamAckIssueCount * 1000,
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
    streamAckClean: scored[0].streamAckClean,
    streamAckIssueCount: scored[0].streamAckIssueCount,
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

function evaluateStreamAckWorkerGuardrail(reportFiles) {
  if (!failOnStreamAckWorkerFailures) {
    return { pass: true, failures: [] };
  }
  const failures = [];
  for (const path of reportFiles) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      if (!report.streamAckWorkers?.delta) {
        continue;
      }
      const issues = streamAckWorkerIssues(report);
      if (issues.failedDelta > maxStreamAckWorkerFailedDelta) {
        failures.push(`${path}: stream-ack failedDelta ${issues.failedDelta} > ${maxStreamAckWorkerFailedDelta}`);
      }
      if (issues.ackFailedDelta > maxStreamAckWorkerAckFailedDelta) {
        failures.push(`${path}: stream-ack ackFailedDelta ${issues.ackFailedDelta} > ${maxStreamAckWorkerAckFailedDelta}`);
      }
      if (issues.completionGap > maxStreamAckCompletionGap) {
        failures.push(`${path}: stream-ack accepted/completed gap ${issues.completionGap} > ${maxStreamAckCompletionGap}`);
      }
    } catch {
      failures.push(`${path}: unable to parse report for stream-ack worker guardrail check`);
    }
  }
  return { pass: failures.length === 0, failures };
}

function evaluateStreamDirectGuardrail(reportFiles) {
  if (!failOnStreamDirectFailures) {
    return { pass: true, failures: [] };
  }
  const failures = [];
  for (const path of reportFiles) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      if (!report.streamDirect?.delta) {
        failures.push(`${path}: missing stream-direct delta`);
        continue;
      }
      if (report.streamDirect?.probes?.after?.ok === false) {
        failures.push(`${path}: stream-direct after probe failed: ${report.streamDirect.probes.after.error ?? report.streamDirect.probes.after.status ?? "unknown"}`);
      }
      const issues = streamDirectIssues(report);
      if (issues.failedDelta > maxStreamDirectFailedDelta) {
        failures.push(`${path}: stream-direct failedDelta ${issues.failedDelta} > ${maxStreamDirectFailedDelta}`);
      }
      if (issues.nackedDelta > maxStreamDirectNackedDelta) {
        failures.push(`${path}: stream-direct nackedDelta ${issues.nackedDelta} > ${maxStreamDirectNackedDelta}`);
      }
      if (issues.termedDelta > maxStreamDirectTermedDelta) {
        failures.push(`${path}: stream-direct termedDelta ${issues.termedDelta} > ${maxStreamDirectTermedDelta}`);
      }
      if (issues.unsupportedDelta > maxStreamDirectUnsupportedDelta) {
        failures.push(`${path}: stream-direct unsupportedDelta ${issues.unsupportedDelta} > ${maxStreamDirectUnsupportedDelta}`);
      }
      if (issues.completionGap > maxStreamDirectCompletionGap) {
        failures.push(`${path}: stream-direct accepted/acked gap ${issues.completionGap} > ${maxStreamDirectCompletionGap}`);
      }
    } catch {
      failures.push(`${path}: unable to parse report for stream-direct guardrail check`);
    }
  }
  return { pass: failures.length === 0, failures };
}

function venueEventMaterializerIssues(report) {
  const delta = report.venueEventMaterializer?.delta;
  if (!delta) {
    return { failedDelta: 0, ackFailedDelta: 0, completionGap: 0, totalIssueCount: 0 };
  }
  const acceptedCommands = Number(report.totalSuccess ?? 0);
  const materializedDelta = Number(delta.materializedDelta ?? 0);
  const failedDelta = Number(delta.failedDelta ?? 0);
  const ackFailedDelta = Number(delta.ackFailedDelta ?? 0);
  const completionGap = Math.max(acceptedCommands - materializedDelta, 0);
  return {
    failedDelta,
    ackFailedDelta,
    completionGap,
    totalIssueCount: failedDelta + ackFailedDelta + completionGap,
  };
}

function evaluateVenueEventMaterializerGuardrail(reportFiles) {
  if (!failOnVenueEventMaterializerFailures) {
    return { pass: true, failures: [] };
  }
  const failures = [];
  for (const path of reportFiles) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      if (!report.venueEventMaterializer?.delta) {
        failures.push(`${path}: missing venue-event-materializer delta`);
        continue;
      }
      if (report.venueEventMaterializer?.probes?.after?.ok === false) {
        failures.push(`${path}: venue-event-materializer after probe failed: ${report.venueEventMaterializer.probes.after.error ?? report.venueEventMaterializer.probes.after.status ?? "unknown"}`);
      }
      const issues = venueEventMaterializerIssues(report);
      if (issues.failedDelta > maxVenueEventMaterializerFailedDelta) {
        failures.push(`${path}: venue-event-materializer failedDelta ${issues.failedDelta} > ${maxVenueEventMaterializerFailedDelta}`);
      }
      if (issues.ackFailedDelta > maxVenueEventMaterializerAckFailedDelta) {
        failures.push(`${path}: venue-event-materializer ackFailedDelta ${issues.ackFailedDelta} > ${maxVenueEventMaterializerAckFailedDelta}`);
      }
      if (issues.completionGap > maxVenueEventMaterializerCompletionGap) {
        failures.push(`${path}: durable-canonical accepted/materialized gap ${issues.completionGap} > ${maxVenueEventMaterializerCompletionGap}`);
      }
    } catch {
      failures.push(`${path}: unable to parse report for venue-event-materializer guardrail check`);
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
      const streamAckIssues = streamAckWorkerIssues(report);
      const directIssues = streamDirectIssues(report);
      const streamIssueCount = streamAckIssues.totalIssueCount + directIssues.totalIssueCount;
      const traceChecked = Number(report.traceChecks?.checked ?? 0);
      const tracePass = Number(report.traceChecks?.pass ?? 0);
      const evidence = canonicalEvidenceSummary(report);
      samples.push({
        path,
        rate: rateMatch ? Number(rateMatch[1]) : Number(report.config?.ratePerSecond ?? 0),
        workers: workerMatch ? Number(workerMatch[1]) : Number(report.config?.workers ?? 0),
        evidence,
        throughputRps: Number(report.throughputRps ?? 0),
        acceptedBusinessOpsRps: Number(report.acceptedBusinessOpsRps ?? 0),
        endToEndSuccessRatePct: quality.endToEndSuccessRatePct,
        validIntentSuccessRatePct: quality.validIntentSuccessRatePct,
        invalidIntentRatePct: quality.invalidIntentRatePct,
        systemFailureRateProxyPct: quality.systemFailureRatePct,
        streamAckClean: streamIssueCount === 0,
        streamAckWorkerFailedDelta: streamAckIssues.failedDelta,
        streamAckWorkerAckFailedDelta: streamAckIssues.ackFailedDelta,
        streamAckCompletionGap: streamAckIssues.completionGap,
        streamDirectClean: directIssues.totalIssueCount === 0,
        streamDirectFailedDelta: directIssues.failedDelta,
        streamDirectNackedDelta: directIssues.nackedDelta,
        streamDirectCompletionGap: directIssues.completionGap,
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
    .filter((sample) => sample.endToEndSuccessRatePct >= 90 && sample.streamAckClean)
    .sort((a, b) => b.throughputRps - a.throughputRps)[0] ?? null;
  const quality95 = [...samples]
    .filter((sample) => sample.endToEndSuccessRatePct >= 95 && sample.streamAckClean)
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
  const evidenceAverages = {
    attempted: avg(samples.map((sample) => sample.evidence.attempted)),
    accepted: avg(samples.map((sample) => sample.evidence.accepted)),
    directAcked: avg(samples.map((sample) => sample.evidence.directAcked)),
    materialized: avg(samples.map((sample) => sample.evidence.materialized)),
    projected: avg(samples.map((sample) => sample.evidence.projected)),
    lag: avg(samples.map((sample) => sample.evidence.lag)),
    p95LatencyMs: avg(samples.map((sample) => sample.evidence.p95LatencyMs)),
    p99LatencyMs: avg(samples.map((sample) => sample.evidence.p99LatencyMs)),
    rates: {
      attemptedPerSecond: avg(samples.map((sample) => sample.evidence.rates.attemptedPerSecond)),
      acceptedPerSecond: avg(samples.map((sample) => sample.evidence.rates.acceptedPerSecond)),
      directAckedPerSecond: avg(samples.map((sample) => sample.evidence.rates.directAckedPerSecond)),
      materializedPerSecond: avg(samples.map((sample) => sample.evidence.rates.materializedPerSecond)),
      projectedPerSecond: avg(samples.map((sample) => sample.evidence.rates.projectedPerSecond)),
    },
    gaps: {
      acceptedToDirectAcked: avg(samples.map((sample) => sample.evidence.gaps.acceptedToDirectAcked)),
      acceptedToMaterialized: avg(samples.map((sample) => sample.evidence.gaps.acceptedToMaterialized)),
      materializedToProjected: avg(samples.map((sample) => sample.evidence.gaps.materializedToProjected)),
    },
  };

  return {
    generatedAt: new Date().toISOString(),
    invalidIntentCodes: invalidCodes,
    sampleCount: samples.length,
    averages,
    evidenceAverages,
    bestByThroughput,
    bestByAccepted,
    qualityCap90: quality90,
    qualityCap95: quality95,
    samples,
  };
}

function qualityFromReport(report, invalidCodes) {
  const streamAckIssues = streamAckWorkerIssues(report);
  const directIssues = streamDirectIssues(report);
  const streamAckSystemFailureCount = streamAckIssues.failedDelta + streamAckIssues.ackFailedDelta + streamAckIssues.completionGap;
  const streamDirectSystemFailureCount =
    directIssues.failedDelta +
    directIssues.nackedDelta +
    directIssues.termedDelta +
    directIssues.unsupportedDelta +
    directIssues.completionGap;
  const streamSystemFailureCount = streamAckSystemFailureCount + streamDirectSystemFailureCount;
  const totalRequests = Number(report.totalRequests ?? 0);
  if (report.quality && typeof report.quality === "object") {
    const baseSystemFailureRatePct = Number(report.quality.systemFailureRatePct ?? 0);
    const streamSystemFailureRatePct =
      totalRequests > 0 ? (streamSystemFailureCount / totalRequests) * 100 : 0;
    return {
      endToEndSuccessRatePct: Number(report.quality.endToEndSuccessRatePct ?? 0),
      validIntentSuccessRatePct: Number(report.quality.validIntentSuccessRatePct ?? 0),
      invalidIntentRatePct: Number(report.quality.invalidIntentRatePct ?? 0),
      systemFailureRatePct: Math.max(baseSystemFailureRatePct, streamSystemFailureRatePct),
    };
  }
  const totalSuccess = Number(report.totalSuccess ?? 0);
  const totalFailures = Number(report.totalFailures ?? 0);
  const invalidIntentRejectCount = invalidCodes.reduce(
    (acc, code) => acc + rejectCount(report, code),
    0,
  );
  const validIntentRequestCount = Math.max(totalRequests - invalidIntentRejectCount, 0);
  const systemFailureProxyCount = Math.max(totalFailures - invalidIntentRejectCount, 0) + streamSystemFailureCount;
  return {
    endToEndSuccessRatePct: totalRequests > 0 ? (totalSuccess / totalRequests) * 100 : 0,
    validIntentSuccessRatePct:
      validIntentRequestCount > 0 ? (totalSuccess / validIntentRequestCount) * 100 : 0,
    invalidIntentRatePct: totalRequests > 0 ? (invalidIntentRejectCount / totalRequests) * 100 : 0,
    systemFailureRatePct: totalRequests > 0 ? (systemFailureProxyCount / totalRequests) * 100 : 0,
  };
}

function streamAckWorkerIssues(report) {
  const delta = report.streamAckWorkers?.delta;
  if (!delta) {
    return {
      failedDelta: 0,
      ackFailedDelta: 0,
      completionGap: 0,
      totalIssueCount: 0,
    };
  }
  const acceptedCommands = Number(report.totalSuccess ?? 0);
  const completedDelta = Number(delta.completedDelta ?? 0);
  const failedDelta = Number(delta.failedDelta ?? 0);
  const ackFailedDelta = Number(delta.ackFailedDelta ?? 0);
  const completionGap = Math.max(acceptedCommands - completedDelta, 0);
  return {
    failedDelta,
    ackFailedDelta,
    completionGap,
    totalIssueCount: failedDelta + ackFailedDelta + completionGap,
  };
}

function streamDirectIssues(report) {
  const delta = report.streamDirect?.delta;
  if (!delta) {
    return {
      failedDelta: 0,
      nackedDelta: 0,
      termedDelta: 0,
      unsupportedDelta: 0,
      completionGap: 0,
      totalIssueCount: 0,
    };
  }
  const acceptedCommands = Number(report.totalSuccess ?? 0);
  const ackedDelta = Number(delta.ackedDelta ?? 0);
  const failedDelta = Number(delta.failedDelta ?? 0);
  const nackedDelta = Number(delta.nackedDelta ?? 0);
  const termedDelta = Number(delta.termedDelta ?? 0);
  const unsupportedDelta = Number(delta.unsupportedDelta ?? 0);
  const completionGap = Math.max(acceptedCommands - ackedDelta, 0);
  return {
    failedDelta,
    nackedDelta,
    termedDelta,
    unsupportedDelta,
    completionGap,
    totalIssueCount: failedDelta + nackedDelta + termedDelta + unsupportedDelta + completionGap,
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
  lines.push(`- attempted: ${kpiSummary.evidenceAverages.attempted.toFixed(2)}`);
  lines.push(`- accepted: ${kpiSummary.evidenceAverages.accepted.toFixed(2)}`);
  lines.push(`- direct-acked: ${kpiSummary.evidenceAverages.directAcked.toFixed(2)}`);
  lines.push(`- materialized: ${kpiSummary.evidenceAverages.materialized.toFixed(2)}`);
  lines.push(`- projected: ${kpiSummary.evidenceAverages.projected.toFixed(2)}`);
  lines.push(`- lag: ${kpiSummary.evidenceAverages.lag.toFixed(2)}`);
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
  lines.push("## Gate Evidence");
  lines.push("| Rate | Workers | Attempted | Accepted | Direct-Acked | Materialized | Projected | Lag | p95 ms | p99 ms | Accepted/Materialized Gap | Materialized/Projected Gap |");
  lines.push("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
  for (const sample of kpiSummary.samples) {
    lines.push(
      `| ${sample.rate} | ${sample.workers} | ${sample.evidence.attempted} | ${sample.evidence.accepted} | ${sample.evidence.directAcked} | ${sample.evidence.materialized} | ${sample.evidence.projected} | ${sample.evidence.lag} | ${sample.evidence.p95LatencyMs.toFixed(2)} | ${sample.evidence.p99LatencyMs.toFixed(2)} | ${sample.evidence.gaps.acceptedToMaterialized} | ${sample.evidence.gaps.materializedToProjected} |`,
    );
  }
  lines.push("");
  lines.push("## Per Sample");
  lines.push("| Rate | Workers | Throughput RPS | Accepted RPS | E2E Success % | Valid-Intent Success % | Invalid-Intent % | System-Failure % (proxy) | Stream Clean | p95 ms | p99 ms |");
  lines.push("|---:|---:|---:|---:|---:|---:|---:|---:|:---:|---:|---:|");
  for (const sample of kpiSummary.samples) {
    lines.push(
      `| ${sample.rate} | ${sample.workers} | ${sample.throughputRps.toFixed(2)} | ${sample.acceptedBusinessOpsRps.toFixed(2)} | ${sample.endToEndSuccessRatePct.toFixed(2)} | ${sample.validIntentSuccessRatePct.toFixed(2)} | ${sample.invalidIntentRatePct.toFixed(2)} | ${sample.systemFailureRateProxyPct.toFixed(2)} | ${sample.streamAckClean ? "yes" : "no"} | ${sample.p95LatencyMs.toFixed(2)} | ${sample.p99LatencyMs.toFixed(2)} |`,
    );
  }
  lines.push("");
  return lines.join("\n");
}
