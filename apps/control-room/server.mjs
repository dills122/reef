#!/usr/bin/env node
import http, { createServer } from "node:http";
import https from "node:https";
import { createReadStream } from "node:fs";
import {
  mkdirSync,
  readdirSync,
  readFileSync,
  statSync,
} from "node:fs";
import { dirname, extname, join, resolve, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { canonicalEvidenceSummary } from "../../scripts/dev/lib/report-taxonomy.mjs";
import { deriveDevUrls, loadDotEnv } from "../../scripts/dev/lib/dev-utils.mjs";

loadDotEnv();

const appDir = dirname(fileURLToPath(import.meta.url));
const publicDir = resolve(appDir, "public");
const stateRoot = resolve(process.env.REEF_CONTROL_ROOM_STATE_DIR || "/tmp/reef-control-room");
const runRoot = resolve(stateRoot, "runs");
const host = process.env.REEF_CONTROL_ROOM_HOST || "127.0.0.1";
const port = Number(process.env.REEF_CONTROL_ROOM_PORT || "3015");
const runtimeUrl = process.env.REEF_CONTROL_ROOM_RUNTIME_URL || deriveDevUrls().runtimeUrl;
const engineUrl = process.env.REEF_CONTROL_ROOM_ENGINE_URL || deriveDevUrls().engineUrl;

mkdirSync(runRoot, { recursive: true });

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url || "/", `http://${request.headers.host || `${host}:${port}`}`);
    if (url.pathname.startsWith("/api/")) {
      await routeApi(request, response, url);
      return;
    }
    await serveStatic(response, url.pathname);
  } catch (error) {
    sendJson(response, 500, { error: "CONTROL_ROOM_ERROR", message: String(error?.message || error) });
  }
});

server.listen(port, host, () => {
  console.log(`reef control room listening at http://${host}:${port}`);
  console.log(`runtime=${runtimeUrl}`);
  console.log(`state=${runRoot}`);
});

async function routeApi(request, response, url) {
  if (request.method === "GET" && url.pathname === "/api/config") {
    sendJson(response, 200, {
      runtimeUrl,
      engineUrl,
      stateRoot,
      runRoot,
      mode: "monitor-only",
    });
    return;
  }

  if (request.method === "GET" && url.pathname === "/api/snapshot") {
    const runId = url.searchParams.get("runId") || "";
    sendJson(response, 200, await buildSnapshot(runId));
    return;
  }

  if (request.method === "GET" && url.pathname === "/api/runs") {
    sendJson(response, 200, { runs: listRuns() });
    return;
  }

  const runMatch = url.pathname.match(/^\/api\/runs\/([^/]+)$/);
  if (request.method === "GET" && runMatch) {
    const run = readRun(decodeURIComponent(runMatch[1]));
    sendJson(response, run ? 200 : 404, run || { error: "RUN_NOT_FOUND" });
    return;
  }

  const eventMatch = url.pathname.match(/^\/api\/runs\/([^/]+)\/events$/);
  if (request.method === "GET" && eventMatch) {
    streamRunEvents(response, decodeURIComponent(eventMatch[1]));
    return;
  }

  sendJson(response, 404, { error: "NOT_FOUND" });
}

async function buildSnapshot(runId) {
  const probes = await Promise.all([
    probeJson("runtime", `${runtimeUrl}/health`),
    probeJson("commandAccounting", `${runtimeUrl}/internal/commands/accounting?runId=${encodeURIComponent(runId)}`),
    probeJson("streamAckHealth", `${runtimeUrl}/internal/stream-ack/health`),
    probeJson("streamAckWorker", `${runtimeUrl}/internal/stream-ack/worker/stats`),
    probeJson("materializer", `${runtimeUrl}/internal/venue-event-materializer/stats`),
    probeJson("projector", `${runtimeUrl}/internal/projector/status`),
    probeJson("hotPath", `${runtimeUrl}/internal/perf/hot-path`),
    probeJson("dbPools", `${runtimeUrl}/internal/perf/db-pools`),
  ]);
  const byName = Object.fromEntries(probes.map((probe) => [probe.name, probe]));
  return {
    sampledAt: new Date().toISOString(),
    runId,
    runtimeUrl,
    engineUrl,
    probes: byName,
    currentRun: runId ? readRun(runId) : null,
    metrics: normalizeSnapshot(byName),
  };
}

function normalizeSnapshot(probes) {
  const accounting = probes.commandAccounting?.json || {};
  const stream = probes.streamAckHealth?.json || {};
  const worker = probes.streamAckWorker?.json || {};
  const materializer = probes.materializer?.json || {};
  const projector = probes.projector?.json || {};
  const dbPools = probes.dbPools?.json?.pools || [];
  const workerMetrics = worker.metrics || {};
  const materializerMetrics = materializer.metrics || {};
  return {
    accepted: numberOrZero(accounting.accepted),
    completed: numberOrZero(accounting.completed),
    failed: numberOrZero(accounting.failed),
    active: numberOrZero(accounting.active),
    accountingGap: numberOrZero(accounting.accountingGap),
    streamAvailable: stream.available === true,
    streamMessages: numberOrZero(stream.messages),
    publishInFlight: numberOrZero(stream.publishInFlight),
    publishQueueDepth: numberOrZero(stream.publishQueueDepth),
    publishFailed: numberOrZero(stream.publishFailed),
    workerCompleted: numberOrZero(workerMetrics.completed),
    workerFailed: numberOrZero(workerMetrics.failed),
    workerAckFailed: numberOrZero(workerMetrics.ackFailed),
    workerLag: sum(worker.consumerMetrics || [], "streamLag"),
    materialized: numberOrZero(materializerMetrics.materialized),
    materializerFailed: numberOrZero(materializerMetrics.failed),
    materializerAckFailed: numberOrZero(materializerMetrics.ackFailed),
    materializerLag: numberOrZero(materializerMetrics.materializerLag),
    projected: numberOrZero(projector.projectedCount),
    projectorLag: numberOrZero(projector.lag),
    dbAwaiting: sum(dbPools, "threadsAwaitingConnection"),
    dbActive: sum(dbPools, "activeConnections"),
  };
}

async function probeJson(name, url) {
  const started = Date.now();
  try {
    const response = await requestText(url, 1500);
    let json = null;
    try {
      json = response.body ? JSON.parse(response.body) : null;
    } catch {
      json = null;
    }
    return {
      name,
      ok: response.status >= 200 && response.status < 300,
      status: response.status,
      latencyMs: Date.now() - started,
      json,
      error: response.status >= 200 && response.status < 300 ? "" : response.body.slice(0, 300),
    };
  } catch (error) {
    return {
      name,
      ok: false,
      status: 0,
      latencyMs: Date.now() - started,
      json: null,
      error: String(error?.message || error),
    };
  }
}

function requestText(rawUrl, timeoutMs) {
  return new Promise((resolvePromise, reject) => {
    const parsed = new URL(rawUrl);
    const client = parsed.protocol === "https:" ? https : http;
    const request = client.request(
      parsed,
      {
        method: "GET",
        timeout: timeoutMs,
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => {
          resolvePromise({
            status: response.statusCode || 0,
            body: Buffer.concat(chunks).toString("utf8"),
          });
        });
      },
    );
    request.on("timeout", () => {
      request.destroy(new Error(`request timed out after ${timeoutMs}ms`));
    });
    request.on("error", reject);
    request.end();
  });
}

function listRuns() {
  return readdirSync(runRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => readRun(entry.name))
    .filter(Boolean)
    .map(summarizeRun)
    .sort((left, right) => String(right.startedAt).localeCompare(String(left.startedAt)));
}

function readRun(runId) {
  const dir = runDir(safeRunId(runId));
  const recordPath = join(dir, "run.json");
  try {
    const record = JSON.parse(readFileSync(recordPath, "utf8"));
    return {
      ...record,
      evidence: readEvidenceForRun(dir),
      recentEvents: readRecentEvents(join(dir, "stdout.ndjson"), 200),
      artifacts: listArtifacts(dir),
    };
  } catch {
    return null;
  }
}

function summarizeRun(run) {
  if (!run) return null;
  return {
    runId: run.runId,
    kind: run.kind,
    status: run.status,
    startedAt: run.startedAt,
    completedAt: run.completedAt,
    exitCode: run.exitCode,
    command: run.command,
    options: run.options,
    artifactDir: run.artifactDir,
    evidence: run.evidence,
  };
}

function readEvidenceForRun(dir) {
  const candidates = findJsonReports(dir);
  const evidenceRows = [];
  for (const path of candidates) {
    try {
      const report = JSON.parse(readFileSync(path, "utf8"));
      if (report && (report.totalRequests !== undefined || report.unitMetrics || report.throughput)) {
        evidenceRows.push({
          path,
          name: relative(dir, path),
          evidence: canonicalEvidenceSummary(report),
          latencyMs: report.latencyMs || {},
          quality: report.quality || {},
          traceChecks: report.traceChecks || {},
        });
      }
    } catch {
      // Ignore partial reports while a run is still writing.
    }
  }
  return evidenceRows;
}

function findJsonReports(dir) {
  const out = [];
  try {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        out.push(...findJsonReports(path));
      } else if (entry.isFile() && entry.name.endsWith(".json") && entry.name !== "run.json") {
        out.push(path);
      }
    }
  } catch {
    return out;
  }
  return out;
}

function listArtifacts(dir) {
  try {
    return readdirSync(dir, { withFileTypes: true })
      .map((entry) => {
        const path = join(dir, entry.name);
        const stats = statSync(path);
        return {
          name: entry.name,
          type: entry.isDirectory() ? "directory" : "file",
          path,
          bytes: entry.isFile() ? stats.size : 0,
          modifiedAt: stats.mtime.toISOString(),
        };
      })
      .sort((left, right) => left.name.localeCompare(right.name));
  } catch {
    return [];
  }
}

function streamRunEvents(response, runId) {
  const dir = runDir(safeRunId(runId));
  const logPath = join(dir, "stdout.ndjson");
  response.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache",
    Connection: "keep-alive",
  });
  let offset = 0;
  const send = () => {
    let text = "";
    try {
      text = readFileSync(logPath, "utf8");
    } catch {
      text = "";
    }
    if (text.length > offset) {
      const chunk = text.slice(offset);
      offset = text.length;
      for (const line of chunk.split("\n").filter(Boolean)) {
        response.write(`data: ${line}\n\n`);
      }
    }
  };
  send();
  const interval = setInterval(send, 1000);
  response.on("close", () => clearInterval(interval));
}

async function serveStatic(response, rawPath) {
  const requested = rawPath === "/" ? "/index.html" : rawPath;
  const path = resolve(publicDir, `.${decodeURIComponent(requested)}`);
  if (!isInside(publicDir, path)) {
    response.writeHead(403);
    response.end("forbidden");
    return;
  }
  try {
    const stats = statSync(path);
    if (!stats.isFile()) throw new Error("not a file");
    response.writeHead(200, { "Content-Type": contentType(path) });
    createReadStream(path).pipe(response);
  } catch {
    response.writeHead(404);
    response.end("not found");
  }
}

function sendJson(response, status, body) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(body, null, 2));
}

function readRecentEvents(path, limit) {
  try {
    return readFileSync(path, "utf8")
      .split("\n")
      .filter(Boolean)
      .slice(-limit)
      .map((line) => JSON.parse(line));
  } catch {
    return [];
  }
}

function runDir(runId) {
  return resolve(runRoot, safeRunId(runId));
}

function safeRunId(value) {
  const cleaned = String(value || "").replace(/[^a-zA-Z0-9_.:-]/g, "-").slice(0, 120);
  if (!cleaned) throw new Error("run id is required");
  return cleaned;
}

function contentType(path) {
  switch (extname(path)) {
    case ".html":
      return "text/html; charset=utf-8";
    case ".css":
      return "text/css; charset=utf-8";
    case ".js":
      return "text/javascript; charset=utf-8";
    case ".json":
      return "application/json; charset=utf-8";
    default:
      return "application/octet-stream";
  }
}

function isInside(parent, child) {
  const rel = relative(parent, child);
  return rel === "" || (!rel.startsWith("..") && !resolve(rel).startsWith(".."));
}

function numberOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function sum(rows, key) {
  return rows.reduce((total, row) => total + numberOrZero(row?.[key]), 0);
}
