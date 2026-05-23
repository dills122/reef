import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

loadDotEnv();
const { runtimeUrl, engineUrl } = deriveDevUrls();
const duration = env("DEV_STRESS_DURATION", "30s");
const workers = env("DEV_STRESS_WORKERS", "12");
const traceLimit = env("DEV_STRESS_TRACE_CHECK_LIMIT", "100");
const out = env("DEV_STRESS_REPORT_OUT", "/tmp/reef-load-report-dev-stress.json");
const mode = env("DEV_STRESS_MODE", "strict-lifecycle");
const profile = env("DEV_STRESS_PROFILE", "default");
const telemetryIntervalMs = Number(env("DEV_STRESS_TELEMETRY_INTERVAL_MS", "1000"));
const minSuccessRatePct = Number(env("DEV_STRESS_MIN_SUCCESS_RATE_PCT", "90"));
const sweepWorkers = parseCsvInts(env("DEV_STRESS_SWEEP_WORKERS", ""));
const rates = parseCsvInts(env("DEV_STRESS_RATES", "100,200,300,400"));
const artifactDir = env("DEV_STRESS_ARTIFACT_DIR", "/tmp");

const baseOut = out.replace(/\.json$/, "");
mkdirSync(artifactDir, { recursive: true });
const telemetryOut = join(artifactDir, `${baseOut.split("/").pop()}-telemetry.ndjson`);
const recommendationOut = join(artifactDir, `${baseOut.split("/").pop()}-recommendation.json`);
const actionMix = resolveActionMix(profile);

console.log(`running stepped stress profile against ${runtimeUrl} (mode=${mode}, profile=${profile})`);

const telemetry = startTelemetryCapture({
  outPath: telemetryOut,
  intervalMs: telemetryIntervalMs,
  runtimeUrl,
  engineUrl,
});
try {
  for (const rate of rates) {
    if (sweepWorkers.length > 0) {
      for (const workerCount of sweepWorkers) {
        await runStressStep({
          runtimeUrl,
          duration,
          workers: String(workerCount),
          rate,
          mode,
          traceLimit,
          actionMix,
          reportOut: `${baseOut}-rate-${rate}-workers-${workerCount}.json`,
        });
      }
    } else {
      await runStressStep({
        runtimeUrl,
        duration,
        workers,
        rate,
        mode,
        traceLimit,
        actionMix,
        reportOut: `${baseOut}-rate-${rate}.json`,
      });
    }
  }
} finally {
  await telemetry.stop();
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

const recommendation = buildRecommendation(reportFiles);
if (recommendation) {
  writeFileSync(recommendationOut, JSON.stringify(recommendation, null, 2));
  console.log("recommended settings:");
  console.log(
    `  workers=${recommendation.workers} rate=${recommendation.rate} throughput=${recommendation.throughputRps.toFixed(2)} accepted=${recommendation.acceptedRps.toFixed(2)} p95=${recommendation.p95Ms.toFixed(2)}ms p99=${recommendation.p99Ms.toFixed(2)}ms score=${recommendation.score.toFixed(2)}`,
  );
  console.log(`  ${recommendationOut}`);
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

function resolveActionMix(profileName) {
  if (profileName === "strict-clean") {
    return { submit: "80", modify: "15", cancel: "5" };
  }
  if (profileName === "capacity-heavy") {
    return { submit: "68", modify: "24", cancel: "8" };
  }
  return { submit: "70", modify: "20", cancel: "10" };
}

async function runStressStep({ runtimeUrl, duration, workers, rate, mode, traceLimit, actionMix, reportOut }) {
  console.log(`step rate=${rate} rps workers=${workers}`);
  await run(
    "go",
    [
      "run",
      "./cmd/load-tester",
      "--base-url",
      runtimeUrl,
      "--duration",
      duration,
      "--workers",
      workers,
      "--rate",
      String(rate),
      "--mode",
      mode,
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
    { name: "engine.health", url: `${engineUrl}/health` },
    { name: "engine.metrics", url: `${engineUrl}/actuator/prometheus` },
  ];
  const results = [];
  for (const probe of probes) {
    const started = Date.now();
    try {
      const response = await fetch(probe.url, { method: "GET" });
      results.push({
        name: probe.name,
        status: response.status,
        ok: response.ok,
        latencyMs: Date.now() - started,
      });
    } catch (error) {
      results.push({
        name: probe.name,
        ok: false,
        latencyMs: Date.now() - started,
        error: String(error.message || error),
      });
    }
  }
  return { sampledAt, probes: results };
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
