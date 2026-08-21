import { mkdirSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { dirname, resolve } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import { deriveDevUrls, env, loadDotEnv } from "./lib/dev-utils.mjs";
import { buildProjectionDrainReport } from "./lib/projection-drain-report.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const projectorUrls = csv(
  env("DEV_PROJECTION_DRAIN_PROJECTOR_URLS", defaultProjectorUrls(runtimeUrl).join(",")),
);
const timeoutMs = nonNegativeNumber(env("DEV_PROJECTION_DRAIN_TIMEOUT_MS", "300000"));
const pollMs = Math.max(100, nonNegativeNumber(env("DEV_PROJECTION_DRAIN_POLL_MS", "1000")));
const reportOut = resolve(env("DEV_PROJECTION_DRAIN_REPORT_OUT", "/tmp/reef-projection-drain-benchmark.json"));

if (projectorUrls.length === 0) throw new Error("DEV_PROJECTION_DRAIN_PROJECTOR_URLS must contain at least one URL");

console.log(`measuring fixed projection backlog across ${projectorUrls.length} projector(s)`);
console.log("  prerequisite: create the backlog with projector services stopped, then start only the projectors");

const readiness = await sampleProjectors();
const resetProbes = await Promise.all(
  projectorUrls.map((baseUrl, index) => requestJson({
    name: `streamAckProjector.${index}.hotPathReset`,
    url: `${baseUrl}/internal/perf/hot-path`,
    method: "POST",
  })),
);
const startedAt = new Date().toISOString();
const samples = [await sampleProjectors()];
const deadline = Date.now() + timeoutMs;

while (samples.at(-1).status.lag > 0 && Date.now() < deadline) {
  await sleep(Math.min(pollMs, Math.max(0, deadline - Date.now())));
  samples.push(await sampleProjectors());
}

const finishedAt = new Date().toISOString();
const report = buildProjectionDrainReport({
  startedAt,
  finishedAt,
  projectorUrls,
  samples,
  timedOut: samples.at(-1).status.lag > 0,
});
report.prerequisite = "Backlog must be loaded with projectors stopped; no canonical intake may run during measurement.";
report.readiness = readiness;
report.hotPathResetProbes = resetProbes;
if (!readiness.probes.status.every((probe) => probe.ok)) {
  report.failures.push("one or more projectors were unavailable before measurement");
}
if (!resetProbes.every((probe) => probe.ok)) {
  report.failures.push("one or more projector hot-path metric resets failed");
}
report.failures = [...new Set(report.failures)];

mkdirSync(dirname(reportOut), { recursive: true });
writeFileSync(reportOut, JSON.stringify(report, null, 2));

console.log(
  `  initialLag=${report.result.initialLag} finalLag=${report.result.finalLag} ` +
    `drained=${report.result.drainedWorkItems} elapsedMs=${report.result.elapsedMs} ` +
    `drainRps=${report.result.drainRps.toFixed(2)} fixedBacklog=${report.result.canonicalCeilingStable}`,
);
console.log(`  report=${reportOut}`);
if (report.failures.length > 0) {
  console.error("projection drain benchmark failed:");
  for (const failure of report.failures) console.error(`  - ${failure}`);
  process.exitCode = 1;
}

async function sampleProjectors() {
  const sampledAt = new Date().toISOString();
  const status = await Promise.all(projectorUrls.map((baseUrl, index) => requestJson({
    name: `streamAckProjector.${index}.status`,
    url: `${baseUrl}/internal/projector/status`,
  })));
  const hotPath = await Promise.all(projectorUrls.map((baseUrl, index) => requestJson({
    name: `streamAckProjector.${index}.hotPath`,
    url: `${baseUrl}/internal/perf/hot-path`,
  })));
  const dbPools = await Promise.all(projectorUrls.map((baseUrl, index) => requestJson({
    name: `streamAckProjector.${index}.dbPools`,
    url: `${baseUrl}/internal/perf/db-pools`,
  })));
  return {
    sampledAt,
    status: aggregateStatus(status),
    probes: { status, hotPath, dbPools },
  };
}

function aggregateStatus(probes) {
  const successful = probes.filter((probe) => probe.ok && probe.json);
  const watermarksByPartition = new Map();
  for (const probe of successful) {
    for (const watermark of probe.json.watermarks ?? []) {
      const key = `${watermark.projectionName ?? ""}:${watermark.partition ?? watermark.partitionId}`;
      const current = watermarksByPartition.get(key);
      if (!current || Number(watermark.lastPartitionSequence ?? 0) >= Number(current.lastPartitionSequence ?? 0)) {
        watermarksByPartition.set(key, watermark);
      }
    }
  }
  const watermarks = [...watermarksByPartition.values()];
  return {
    lag: watermarks.reduce((sum, watermark) => sum + Number(watermark.lag ?? 0), 0),
    projectedCount: Math.max(0, ...successful.map((probe) => Number(probe.json.projectedCount ?? 0))),
    metrics: {
      projected: successful.reduce((sum, probe) => sum + Number(probe.json.metrics?.projected ?? 0), 0),
      batches: successful.reduce((sum, probe) => sum + Number(probe.json.metrics?.batches ?? 0), 0),
      failed: successful.reduce((sum, probe) => sum + Number(probe.json.metrics?.failed ?? 0), 0),
      retryAttempts: successful.reduce((sum, probe) => sum + Number(probe.json.metrics?.retryAttempts ?? 0), 0),
      retryExhausted: successful.reduce((sum, probe) => sum + Number(probe.json.metrics?.retryExhausted ?? 0), 0),
    },
    projectors: successful.map((probe) => probe.json),
    watermarks,
  };
}

function requestJson({ name, url, method = "GET" }) {
  const started = Date.now();
  return new Promise((resolveRequest) => {
    const parsed = new URL(url);
    const client = parsed.protocol === "https:" ? https : http;
    const request = client.request(
      parsed,
      {
        method,
        timeout: 5_000,
        headers: { "X-Reef-Internal-Route": "true" },
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => {
          const body = Buffer.concat(chunks).toString("utf8");
          let json = null;
          try {
            json = body ? JSON.parse(body) : null;
          } catch {
            // The probe remains failed below when a JSON endpoint returns invalid JSON.
          }
          resolveRequest({
            name,
            url,
            ok: response.statusCode >= 200 && response.statusCode < 300 && json !== null,
            status: response.statusCode,
            latencyMs: Date.now() - started,
            json,
          });
        });
      },
    );
    request.on("timeout", () => request.destroy(new Error("request timed out")));
    request.on("error", (error) => resolveRequest({
      name,
      url,
      ok: false,
      status: 0,
      latencyMs: Date.now() - started,
      error: error.message,
      json: null,
    }));
    request.end();
  });
}

function defaultProjectorUrls(baseRuntimeUrl) {
  const parsed = new URL(baseRuntimeUrl);
  const ports = [
    env("REEF_PLATFORM_PROJECTOR_0_HOST_PORT", env("REEF_PLATFORM_PROJECTOR_HOST_PORT", "8084")),
    env("REEF_PLATFORM_PROJECTOR_1_HOST_PORT", "8085"),
    env("REEF_PLATFORM_PROJECTOR_2_HOST_PORT", "8088"),
    env("REEF_PLATFORM_PROJECTOR_3_HOST_PORT", "8089"),
  ];
  return ports.map((port) => `${parsed.protocol}//${parsed.hostname}:${port}`);
}

function csv(raw) {
  return String(raw ?? "").split(",").map((value) => value.trim()).filter(Boolean);
}

function nonNegativeNumber(raw) {
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed < 0) throw new Error(`expected a non-negative number, got ${raw}`);
  return parsed;
}
