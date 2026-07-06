import { basename, join } from "node:path";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { performance } from "node:perf_hooks";
import { setTimeout as sleep } from "node:timers/promises";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";
import {
  captureDbDiagnosticsSnapshot,
  defaultDiagnosticSchemas,
  summarizeDiagnosticsDelta,
} from "./lib/db-diagnostics.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const duration = env("DEV_INTAKE_DURATION", "30s");
const workers = env("DEV_INTAKE_WORKERS", "256");
const rate = env("DEV_INTAKE_RATE", "10000");
const rateSchedule = env("DEV_INTAKE_RATE_SCHEDULE", "precise");
const actorIdPrefix = env("DEV_INTAKE_ACTOR_ID_PREFIX", "bot");
const artifactDir = env("DEV_INTAKE_ARTIFACT_DIR", "/tmp");
const out = env("DEV_INTAKE_REPORT_OUT", "/tmp/reef-intake-bench.json");
const runKind = env("DEV_INTAKE_RUN_KIND", "intake-bench");
const scenarioId = env("DEV_INTAKE_SCENARIO_ID", "raw-intake");
const captureDbDiagnostics = env("DEV_INTAKE_CAPTURE_DB_DIAGNOSTICS", "1") !== "0";
const captureCommandAccounting = env("DEV_INTAKE_CAPTURE_COMMAND_ACCOUNTING", "1") !== "0";
const captureIntakeCounters = env("DEV_INTAKE_CAPTURE_INTAKE_COUNTERS", "1") !== "0";
const commandDrainWaitMs = Number(env("DEV_INTAKE_COMMAND_DRAIN_WAIT_MS", "30000"));
const commandDrainPollMs = Number(env("DEV_INTAKE_COMMAND_DRAIN_POLL_MS", "1000"));
const failOnAccountingGap = env("DEV_INTAKE_FAIL_ON_ACCOUNTING_GAP", "0") === "1";
const dbDiagnosticsService = env("DEV_INTAKE_DB_SERVICE", "postgres");
const dbDiagnosticsUser = env("DEV_INTAKE_DB_USER", "reef");
const dbDiagnosticsName = env("DEV_INTAKE_DB_NAME", "reef");
const dbDiagnosticsSchemas = parseCsv(env("DEV_INTAKE_DB_SCHEMAS", defaultDiagnosticSchemas.join(",")));
const baseOut = out.replace(/\.json$/, "");
const reportBaseName = basename(baseOut);
const reportOut = join(artifactDir, `${reportBaseName}-workers-${workers}-rate-${rate}.json`);
const diagnosticsDir = join(artifactDir, `${reportBaseName}-db-diagnostics-workers-${workers}-rate-${rate}`);
const runId = env("DEV_INTAKE_RUN_ID", `${reportBaseName}-workers-${workers}-rate-${rate}-${Date.now()}`);

mkdirSync(artifactDir, { recursive: true });

console.log(`running intake bench against ${runtimeUrl}`);
console.log(`  duration=${duration} workers=${workers} rate=${rate} schedule=${rateSchedule} actorPrefix=${actorIdPrefix} runId=${runId}`);

let preDbDiagnostics = null;
let postDbDiagnostics = null;
let beforeAccounting = null;
let afterAccounting = null;
let drainedAccounting = null;
let beforeIntakeCounters = null;
let afterIntakeCounters = null;
let drainedIntakeCounters = null;
if (captureDbDiagnostics) {
  preDbDiagnostics = await captureDbDiagnosticsSnapshot({
    diagnosticsDir,
    stage: "pre",
    service: dbDiagnosticsService,
    dbUser: dbDiagnosticsUser,
    dbName: dbDiagnosticsName,
    schemas: dbDiagnosticsSchemas,
  });
}
if (captureCommandAccounting) {
  beforeAccounting = await sampleCommandAccounting(runtimeUrl, runId);
}
if (captureIntakeCounters) {
  beforeIntakeCounters = await sampleIntakeCounters(runtimeUrl);
}

try {
  await run(
    "go",
    [
      "run",
      "./cmd/intake-bench",
      "--base-url",
      runtimeUrl,
      "--duration",
      duration,
      "--workers",
      workers,
      "--rate",
      rate,
      "--rate-schedule",
      rateSchedule,
      "--actor-id-prefix",
      actorIdPrefix,
      "--run-id",
      runId,
      "--run-kind",
      runKind,
      "--scenario-id",
      scenarioId,
      "--pretty-summary",
      "--report-out",
      reportOut,
    ],
    { cwd: "services/simulator" },
  );
} finally {
  if (captureIntakeCounters) {
    afterIntakeCounters = await sampleIntakeCounters(runtimeUrl);
  }
  if (captureCommandAccounting) {
    afterAccounting = await sampleCommandAccounting(runtimeUrl, runId);
    drainedAccounting = await waitForCommandDrain({
      runtimeUrl,
      runId,
      initialProbe: afterAccounting,
      timeoutMs: commandDrainWaitMs,
      pollMs: commandDrainPollMs,
    });
  }
  if (captureIntakeCounters) {
    drainedIntakeCounters = await sampleIntakeCounters(runtimeUrl);
  }
  if (captureDbDiagnostics) {
    postDbDiagnostics = await captureDbDiagnosticsSnapshot({
      diagnosticsDir,
      stage: "post",
      service: dbDiagnosticsService,
      dbUser: dbDiagnosticsUser,
      dbName: dbDiagnosticsName,
      schemas: dbDiagnosticsSchemas,
    });
  }
}

if (captureDbDiagnostics && existsSync(reportOut)) {
  const report = JSON.parse(readFileSync(reportOut, "utf8"));
  report.dbDiagnostics = {
    diagnosticsDir,
    schemas: dbDiagnosticsSchemas,
    pre: preDbDiagnostics,
    post: postDbDiagnostics,
    delta: summarizeDiagnosticsDelta(preDbDiagnostics, postDbDiagnostics),
  };
  writeFileSync(reportOut, JSON.stringify(report, null, 2));
}

if (captureCommandAccounting && existsSync(reportOut)) {
  const report = JSON.parse(readFileSync(reportOut, "utf8"));
  const durationSeconds = Number(report.durationSeconds ?? 0) || parseDurationSeconds(duration);
  const commandAccounting = buildCommandAccounting({
    runId,
    durationSeconds,
    beforeProbe: beforeAccounting,
    afterProbe: afterAccounting,
    drainedProbe: drainedAccounting,
  });
  report.commandAccounting = commandAccounting;
  writeFileSync(reportOut, JSON.stringify(report, null, 2));
  if (commandAccounting.delta) {
    const delta = commandAccounting.delta;
    console.log(
      `command-accounting acceptedDelta=${delta.acceptedDelta} terminalDuringLoad=${delta.terminalDelta} activeAfter=${delta.activeAfter} gapAfter=${delta.accountingGapAfter} completedDuringLoadRps=${delta.completedDuringLoadRps.toFixed(2)}`,
    );
    if (delta.drain) {
      console.log(
        `command-drain terminalFinal=${delta.drain.terminalFinal} activeFinal=${delta.drain.activeFinal} gapFinal=${delta.drain.accountingGapFinal} drainedCount=${delta.drain.drainedTerminalCount} drainSeconds=${delta.drain.drainSeconds.toFixed(2)} drainRps=${delta.drain.drainRps.toFixed(2)} timedOut=${delta.drain.timedOut}`,
      );
    }
    const gap = delta.drain?.accountingGapFinal ?? delta.accountingGapAfter;
    if (failOnAccountingGap && gap !== 0) {
      throw new Error(`command accounting gap for run ${runId}: ${gap}`);
    }
  }
}

if (captureIntakeCounters && existsSync(reportOut)) {
  const report = JSON.parse(readFileSync(reportOut, "utf8"));
  const intakeCounters = buildIntakeCounters({
    before: beforeIntakeCounters,
    after: afterIntakeCounters,
    drained: drainedIntakeCounters,
  });
  report.intakeCounters = intakeCounters;
  writeFileSync(reportOut, JSON.stringify(report, null, 2));
  const d = intakeCounters.drainedDelta ?? intakeCounters.afterDelta;
  if (d) {
    console.log(
      `intake-counters engineFetchedDelta=${d.engineFetchedDelta} eventBatchMaterializedDelta=${d.eventBatchMaterializedDelta} canonicalOutcomesDelta=${d.canonicalOutcomesDelta} projectedDelta=${d.projectedDelta} projectorLag=${d.projectorLagFinal} brokerMessagesFinal=${d.brokerMessagesFinal}`,
    );
  }
}

console.log("intake bench complete. report:");
console.log(`  ${reportOut}`);
if (captureDbDiagnostics) {
  console.log(`  ${diagnosticsDir}`);
}

function parseCsv(raw) {
  return String(raw ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}

async function sampleCommandAccounting(runtimeUrl, runId) {
  return requestJson(`${runtimeUrl}/internal/commands/accounting?runId=${encodeURIComponent(runId)}`);
}

async function sampleIntakeCounters(runtimeUrl) {
  const [streamHealth, materializerStats, canonicalProjectorStatus, orderLifecycleProjectorStatus] = await Promise.all([
    requestJson(`${runtimeUrl}/internal/stream-ack/health`),
    requestJson(`${runtimeUrl}/internal/venue-event-materializer/stats`),
    requestJson(`${runtimeUrl}/internal/projector/status`),
    requestJson(`${runtimeUrl}/internal/order-lifecycle/projector/status`),
  ]);
  return { streamHealth, materializerStats, canonicalProjectorStatus, orderLifecycleProjectorStatus };
}

function buildIntakeCounters({ before, after, drained }) {
  return {
    before: intakeCountersSummary(before),
    after: intakeCountersSummary(after),
    drained: intakeCountersSummary(drained),
    afterDelta: intakeCountersDelta(before, after),
    drainedDelta: intakeCountersDelta(before, drained),
  };
}

function intakeCountersSummary(probe) {
  if (!probe) return null;
  return {
    streamHealth: probe.streamHealth?.json ?? null,
    materializerStats: probe.materializerStats?.json ?? null,
    canonicalProjectorStatus: probe.canonicalProjectorStatus?.json ?? null,
    orderLifecycleProjectorStatus: probe.orderLifecycleProjectorStatus?.json ?? null,
  };
}

function intakeCountersDelta(before, sample) {
  if (!before || !sample) return null;
  const beforeMaterializer = before.materializerStats?.json?.metrics ?? {};
  const sampleMaterializer = sample.materializerStats?.json?.metrics ?? {};
  const beforeCanonicalProjector = before.canonicalProjectorStatus?.json ?? {};
  const sampleCanonicalProjector = sample.canonicalProjectorStatus?.json ?? {};
  const beforeOrderLifecycle = before.orderLifecycleProjectorStatus?.json ?? {};
  const sampleOrderLifecycle = sample.orderLifecycleProjectorStatus?.json ?? {};
  const streamHealth = sample.streamHealth?.json ?? {};
  return {
    engineFetchedDelta: Number(sampleMaterializer.fetched ?? 0) - Number(beforeMaterializer.fetched ?? 0),
    eventBatchMaterializedDelta: Number(sampleMaterializer.materialized ?? 0) - Number(beforeMaterializer.materialized ?? 0),
    canonicalOutcomesDelta:
      Number(sampleMaterializer.materializedOutcomes ?? 0) - Number(beforeMaterializer.materializedOutcomes ?? 0),
    projectedDelta:
      Number(sampleCanonicalProjector.projectedCount ?? 0) - Number(beforeCanonicalProjector.projectedCount ?? 0),
    orderLifecycleProjectedDelta:
      Number(sampleOrderLifecycle.projectedCount ?? 0) - Number(beforeOrderLifecycle.projectedCount ?? 0),
    materializerLastMaterializedAt: sampleMaterializer.lastMaterializedAt ?? "",
    projectorLagFinal: Number(sampleCanonicalProjector.lag ?? 0),
    orderLifecycleLagFinal: Number(sampleOrderLifecycle.lag ?? 0),
    brokerMessagesFinal: Number(streamHealth.messages ?? 0),
  };
}

async function waitForCommandDrain({ runtimeUrl, runId, initialProbe, timeoutMs, pollMs }) {
  const startedAtMs = Date.now();
  const startedAtMonotonicMs = performance.now();
  const maxPolls = Math.max(1, Math.ceil(timeoutMs / Math.max(1, pollMs)) + 1);
  let latest = initialProbe;
  let polls = 0;
  while (
    latest?.json?.available &&
    Number(latest.json.active ?? 0) > 0 &&
    performance.now() - startedAtMonotonicMs < timeoutMs &&
    polls < maxPolls
  ) {
    await sleep(pollMs);
    latest = await sampleCommandAccounting(runtimeUrl, runId);
    polls++;
  }
  const elapsedMs = Math.round(performance.now() - startedAtMonotonicMs);
  return {
    ...(latest ?? {}),
    drainStartedAt: new Date(startedAtMs).toISOString(),
    drainFinishedAt: new Date().toISOString(),
    drainElapsedMs: elapsedMs,
    drainPolls: polls,
    timedOut: Boolean(latest?.json?.available && Number(latest.json.active ?? 0) > 0),
  };
}

function buildCommandAccounting({ runId, durationSeconds, beforeProbe, afterProbe, drainedProbe }) {
  const before = beforeProbe?.json ?? null;
  const after = afterProbe?.json ?? null;
  const drained = drainedProbe?.json ?? null;
  const delta = commandAccountingDelta({
    before,
    after,
    drained,
    durationSeconds,
    drainElapsedMs: Number(drainedProbe?.drainElapsedMs ?? 0),
    drainTimedOut: Boolean(drainedProbe?.timedOut),
  });
  return {
    runId,
    before,
    after,
    drained,
    delta,
    probes: {
      before: probeSummary(beforeProbe),
      after: probeSummary(afterProbe),
      drained: {
        ...probeSummary(drainedProbe),
        drainStartedAt: drainedProbe?.drainStartedAt ?? "",
        drainFinishedAt: drainedProbe?.drainFinishedAt ?? "",
        drainElapsedMs: Number(drainedProbe?.drainElapsedMs ?? 0),
        drainPolls: Number(drainedProbe?.drainPolls ?? 0),
        timedOut: Boolean(drainedProbe?.timedOut),
      },
    },
  };
}

function commandAccountingDelta({ before, after, drained, durationSeconds, drainElapsedMs, drainTimedOut }) {
  if (!before?.available || !after?.available) return null;
  const acceptedDelta = Number(after.accepted ?? 0) - Number(before.accepted ?? 0);
  const completedDelta = Number(after.completed ?? 0) - Number(before.completed ?? 0);
  const failedDelta = Number(after.failed ?? 0) - Number(before.failed ?? 0);
  const terminalDelta = completedDelta + failedDelta;
  const out = {
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
    completedDuringLoadRps: durationSeconds > 0 ? terminalDelta / durationSeconds : 0,
  };
  if (drained?.available) {
    const completedFinalDelta = Number(drained.completed ?? 0) - Number(before.completed ?? 0);
    const failedFinalDelta = Number(drained.failed ?? 0) - Number(before.failed ?? 0);
    const terminalFinalDelta = completedFinalDelta + failedFinalDelta;
    const drainedTerminalCount = Math.max(0, terminalFinalDelta - terminalDelta);
    const drainSeconds = drainElapsedMs > 0 ? drainElapsedMs / 1000 : 0;
    out.drain = {
      completedFinalDelta,
      failedFinalDelta,
      terminalFinalDelta,
      terminalFinal: Number(drained.terminal ?? 0),
      activeFinal: Number(drained.active ?? 0),
      receivedFinal: Number(drained.received ?? 0),
      processingFinal: Number(drained.processing ?? 0),
      staleProcessingFinal: Number(drained.staleProcessing ?? 0),
      accountingGapFinal: Number(drained.accountingGap ?? 0),
      drainedTerminalCount,
      drainSeconds,
      drainRps: drainSeconds > 0 ? drainedTerminalCount / drainSeconds : 0,
      timedOut: drainTimedOut,
    };
  }
  return out;
}

function probeSummary(probe) {
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

async function requestJson(rawUrl) {
  const startedAt = Date.now();
  return new Promise((resolve) => {
    const url = new URL(rawUrl);
    const client = url.protocol === "https:" ? https : http;
    const req = client.request(url, { method: "GET", timeout: 2000, agent: false }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => {
        const body = Buffer.concat(chunks).toString("utf8");
        const result = {
          ok: response.statusCode >= 200 && response.statusCode < 300,
          status: response.statusCode,
          latencyMs: Date.now() - startedAt,
        };
        try {
          result.json = JSON.parse(body);
        } catch (error) {
          result.bodyError = error?.message ?? String(error);
        }
        resolve(result);
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error("request timeout"));
    });
    req.on("error", (error) => {
      resolve({
        ok: false,
        status: 0,
        latencyMs: Date.now() - startedAt,
        error: error?.message ?? String(error),
      });
    });
    req.end();
  });
}
