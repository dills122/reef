import { basename, join } from "node:path";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
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

async function waitForCommandDrain({ runtimeUrl, runId, initialProbe, timeoutMs, pollMs }) {
  const startedAtMs = Date.now();
  let latest = initialProbe;
  while (latest?.json?.available && Number(latest.json.active ?? 0) > 0 && Date.now() - startedAtMs < timeoutMs) {
    await sleep(pollMs);
    latest = await sampleCommandAccounting(runtimeUrl, runId);
  }
  return {
    ...(latest ?? {}),
    drainStartedAt: new Date(startedAtMs).toISOString(),
    drainFinishedAt: new Date().toISOString(),
    drainElapsedMs: Date.now() - startedAtMs,
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
    const req = client.request(url, { method: "GET", timeout: 2000 }, (response) => {
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
