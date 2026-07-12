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
import { expectedRolesForRunProfile } from "../../scripts/dev/lib/run-profile-roles.mjs";

loadDotEnv();

const appDir = dirname(fileURLToPath(import.meta.url));
const publicDir = resolve(appDir, "public");
const stateRoot = resolve(process.env.REEF_CONTROL_ROOM_STATE_DIR || "/tmp/reef-control-room");
const runRoot = resolve(stateRoot, "runs");
const host = process.env.REEF_CONTROL_ROOM_HOST || "127.0.0.1";
const port = Number(process.env.REEF_CONTROL_ROOM_PORT || "3015");
const runtimeUrl = process.env.REEF_CONTROL_ROOM_RUNTIME_URL || deriveDevUrls().runtimeUrl;
const engineUrl = process.env.REEF_CONTROL_ROOM_ENGINE_URL || deriveDevUrls().engineUrl;
const workerUrls = csvEnv(
  "REEF_CONTROL_ROOM_WORKER_URLS",
  localRoleUrls("REEF_PLATFORM_WORKER", ["8082", "8083", "8086", "8087"]),
);
const projectorUrls = csvEnv(
  "REEF_CONTROL_ROOM_PROJECTOR_URLS",
  localRoleUrls("REEF_PLATFORM_PROJECTOR", ["8084", "8085", "8088", "8089"]),
);
const materializerUrls = optionalCsvEnv("REEF_CONTROL_ROOM_MATERIALIZER_URLS");
const profileName = normalizeProfileName(
  process.env.REEF_CONTROL_ROOM_PROFILE || (materializerUrls.length > 0 ? "materializer-soak" : "stream-ack"),
);

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
      workerUrls,
      projectorUrls,
      materializerUrls,
      profile: profileConfig(),
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
  const [probes, workers, materializers, projectors] = await Promise.all([
    Promise.all([
      probeJson("runtime", `${runtimeUrl}/health`),
      probeJson("commandAccounting", `${runtimeUrl}/internal/commands/accounting?runId=${encodeURIComponent(runId)}`),
      probeJson("streamAckHealth", `${runtimeUrl}/internal/stream-ack/health`),
      probeJson("streamAckWorker", `${runtimeUrl}/internal/stream-ack/worker/stats`),
      probeJson("materializer", `${runtimeUrl}/internal/venue-event-materializer/stats`),
      probeJson("projector", `${runtimeUrl}/internal/projector/status`),
      probeJson("hotPath", `${runtimeUrl}/internal/perf/hot-path`),
      probeJson("dbPools", `${runtimeUrl}/internal/perf/db-pools`),
    ]),
    Promise.all(workerUrls.map((url, index) => probeJson(`worker-${index}`, `${url}/internal/stream-ack/worker/stats`))),
    Promise.all(materializerUrls.map((url, index) => probeJson(`materializer-${index}`, `${url}/internal/venue-event-materializer/stats`))),
    Promise.all(projectorUrls.map((url, index) => probeJson(`projector-${index}`, `${url}/internal/projector/status`))),
  ]);
  const byName = Object.fromEntries(probes.map((probe) => [probe.name, probe]));
  const containers = normalizeContainers(workers, materializers, projectors);
  return {
    sampledAt: new Date().toISOString(),
    runId,
    runtimeUrl,
    engineUrl,
    profile: snapshotProfile(containers),
    probes: byName,
    containers,
    currentRun: runId ? readRun(runId) : null,
    metrics: normalizeSnapshot(byName, containers),
  };
}

function normalizeSnapshot(probes, containers = {}) {
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
    workerCompleted: Math.max(numberOrZero(workerMetrics.completed), numberOrZero(containers.workerTotals?.completed)),
    workerFailed: Math.max(numberOrZero(workerMetrics.failed), numberOrZero(containers.workerTotals?.failed)),
    workerAckFailed: Math.max(numberOrZero(workerMetrics.ackFailed), numberOrZero(containers.workerTotals?.ackFailed)),
    workerLag: Math.max(sum(worker.consumerMetrics || [], "streamLag"), numberOrZero(containers.workerTotals?.streamLag)),
    materialized: Math.max(numberOrZero(materializerMetrics.materialized), numberOrZero(containers.materializerTotals?.materialized)),
    materializerFailed: Math.max(numberOrZero(materializerMetrics.failed), numberOrZero(containers.materializerTotals?.failed)),
    materializerAckFailed: Math.max(numberOrZero(materializerMetrics.ackFailed), numberOrZero(containers.materializerTotals?.ackFailed)),
    materializerLag: Math.max(numberOrZero(materializerMetrics.materializerLag), numberOrZero(containers.materializerTotals?.lag)),
    projected: Math.max(numberOrZero(projector.projectedCount), numberOrZero(containers.projectorTotals?.projected)),
    projectorLag: Math.max(numberOrZero(projector.lag), numberOrZero(containers.projectorTotals?.lag)),
    dbAwaiting: sum(dbPools, "threadsAwaitingConnection"),
    dbActive: sum(dbPools, "activeConnections"),
  };
}

function normalizeContainers(workers, materializers, projectors) {
  const workerRows = workers.map((probe) => {
    const metrics = probe.json?.metrics || {};
    const consumers = probe.json?.consumerMetrics || [];
    return {
      name: probe.name,
      url: probe.url,
      ok: probe.ok,
      status: probe.status,
      enabled: probe.json?.enabled === true,
      role: probe.json?.role || "worker",
      partitions: probe.json?.partitions || [],
      completed: numberOrZero(metrics.completed),
      failed: numberOrZero(metrics.failed),
      ackFailed: numberOrZero(metrics.ackFailed),
      streamLag: sum(consumers, "streamLag"),
      ackPending: sum(consumers, "ackPending"),
      error: probe.error,
    };
  });
  const materializerRows = materializers.map((probe) => {
    const metrics = probe.json?.metrics || {};
    return {
      name: probe.name,
      url: probe.url,
      ok: probe.ok,
      status: probe.status,
      enabled: probe.json?.enabled === true,
      role: probe.json?.role || "materializer",
      materialized: numberOrZero(metrics.materialized),
      failed: numberOrZero(metrics.failed),
      ackFailed: numberOrZero(metrics.ackFailed),
      lag: numberOrZero(metrics.materializerLag),
      error: probe.error,
    };
  });
  const projectorRows = projectors.map((probe) => {
    const metrics = probe.json?.metrics || {};
    return {
      name: probe.name,
      url: probe.url,
      ok: probe.ok,
      status: probe.status,
      running: probe.json?.status === "running",
      role: probe.json?.role || "projector",
      partitions: probe.json?.partitions || [],
      projected: numberOrZero(probe.json?.projectedCount),
      failed: numberOrZero(metrics.failed),
      lag: numberOrZero(probe.json?.lag),
      error: probe.error,
    };
  });
  return {
    workers: workerRows,
    materializers: materializerRows,
    projectors: projectorRows,
    workerTotals: {
      enabled: workerRows.filter((row) => row.enabled).length,
      completed: sum(workerRows, "completed"),
      failed: sum(workerRows, "failed"),
      ackFailed: sum(workerRows, "ackFailed"),
      streamLag: sum(workerRows, "streamLag"),
      ackPending: sum(workerRows, "ackPending"),
    },
    materializerTotals: {
      enabled: materializerRows.filter((row) => row.enabled).length,
      materialized: sum(materializerRows, "materialized"),
      failed: sum(materializerRows, "failed"),
      ackFailed: sum(materializerRows, "ackFailed"),
      lag: sum(materializerRows, "lag"),
    },
    projectorTotals: {
      running: projectorRows.filter((row) => row.running).length,
      projected: sum(projectorRows, "projected"),
      failed: sum(projectorRows, "failed"),
      lag: sum(projectorRows, "lag"),
    },
  };
}

function profileConfig() {
  return {
    name: profileName,
    expectedRoles: expectedRolesForRunProfile(profileName),
  };
}

function snapshotProfile(containers) {
  const config = profileConfig();
  const warnings = [];
  const workerOnline = numberOrZero(containers.workerTotals?.enabled);
  const materializerConfigured = (containers.materializers || []).length;
  const materializerOnline = numberOrZero(containers.materializerTotals?.enabled);
  const projectorOnline = numberOrZero(containers.projectorTotals?.running);
  if (isMaterializerProfile(profileName)) {
    if (materializerConfigured === 0) {
      warnings.push("materializer profile selected but REEF_CONTROL_ROOM_MATERIALIZER_URLS is empty");
    } else if (materializerOnline === 0) {
      warnings.push("materializer profile selected but no materializer endpoint is online");
    }
    if (workerOnline > 0) {
      warnings.push("materializer profile selected but stream-ack workers are enabled");
    }
  } else if (profileName === "stream-ack" && workerUrls.length > 0 && workerOnline === 0) {
    warnings.push("stream-ack profile selected but no worker endpoint is enabled");
  }
  if (profileName === "stream-ack" && projectorUrls.length > 0 && projectorOnline === 0) {
    warnings.push("no projector endpoint is running");
  }
  return {
    ...config,
    warnings,
    observedRoles: {
      workers: workerOnline,
      materializers: materializerOnline,
      projectors: projectorOnline,
    },
  };
}

function normalizeProfileName(value) {
  const name = String(value || "").trim();
  if (!name) return "stream-ack";
  if (["materializer", "materializer-soak", "direct-materializer", "venue-event-materializer"].includes(name)) {
    return "materializer-soak";
  }
  if (["direct-nodb", "stream-direct-nodb"].includes(name)) {
    return "direct-nodb";
  }
  return name;
}

function isMaterializerProfile(name) {
  return name === "materializer-soak";
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
      url,
      ok: response.status >= 200 && response.status < 300,
      status: response.status,
      latencyMs: Date.now() - started,
      json,
      error: response.status >= 200 && response.status < 300 ? "" : response.body.slice(0, 300),
    };
  } catch (error) {
    return {
      name,
      url,
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
  const evidence = readEvidenceForRun(dir);
  const artifacts = listArtifacts(dir);
  try {
    const record = JSON.parse(readFileSync(recordPath, "utf8"));
    return {
      ...record,
      profile: record.profile || latestRunProfile(evidence),
      evidence,
      recentEvents: readRecentEvents(join(dir, "stdout.ndjson"), 200),
      artifacts,
    };
  } catch {
    if (evidence.length === 0 && artifacts.length === 0) return null;
    return {
      runId: safeRunId(runId),
      kind: "artifact",
      status: "observed",
      profile: latestRunProfile(evidence),
      command: [],
      startedAt: firstModifiedAt(artifacts),
      completedAt: lastModifiedAt(artifacts),
      exitCode: null,
      artifactDir: dir,
      evidence,
      recentEvents: readRecentEvents(join(dir, "stdout.ndjson"), 200),
      artifacts,
    };
  }
}

function summarizeRun(run) {
  if (!run) return null;
  return {
    runId: run.runId,
    kind: run.kind,
    status: run.status,
    profile: run.profile || latestRunProfile(run.evidence || []),
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
          modifiedAt: statSync(path).mtime.toISOString(),
          evidence: canonicalEvidenceSummary(report),
          stressRunMetadata: report.stressRunMetadata || null,
          latencyMs: report.latencyMs || {},
          quality: report.quality || {},
          traceChecks: report.traceChecks || {},
        });
      }
    } catch {
      // Ignore partial reports while a run is still writing.
    }
  }
  return evidenceRows.sort((left, right) => String(left.modifiedAt).localeCompare(String(right.modifiedAt)));
}

function latestRunProfile(evidenceRows) {
  return evidenceRows
    .slice()
    .reverse()
    .map((row) => row.stressRunMetadata?.runProfile)
    .find(Boolean) || "";
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

function firstModifiedAt(artifacts) {
  return artifacts
    .map((artifact) => artifact.modifiedAt)
    .filter(Boolean)
    .sort()[0] || "";
}

function lastModifiedAt(artifacts) {
  return artifacts
    .map((artifact) => artifact.modifiedAt)
    .filter(Boolean)
    .sort()
    .at(-1) || "";
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

function csvEnv(name, fallback) {
  return String(process.env[name] || fallback)
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}

function optionalCsvEnv(name) {
  if (process.env[name] === undefined) return [];
  return csvEnv(name, "");
}

function localRoleUrls(envPrefix, defaultPorts) {
  const parsed = new URL(runtimeUrl);
  return defaultPorts
    .map((portValue, index) => {
      const port = process.env[`${envPrefix}_${index}_HOST_PORT`] || portValue;
      return `${parsed.protocol}//${parsed.hostname}:${port}`;
    })
    .join(",");
}
