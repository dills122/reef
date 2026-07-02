import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { basename, join } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";
import {
  captureDbDiagnosticsLogs,
  captureDbDiagnosticsSnapshot,
  defaultDiagnosticSchemas,
} from "./lib/db-diagnostics.mjs";

loadDotEnv();
const { runtimeUrl, engineUrl } = deriveDevUrls();
const duration = env("DEV_STRESS_DURATION", "30s");
const workers = env("DEV_STRESS_WORKERS", "12");
const traceLimit = env("DEV_STRESS_TRACE_CHECK_LIMIT", "100");
const out = env("DEV_STRESS_REPORT_OUT", "/tmp/reef-load-report-dev-stress.json");
const mode = env("DEV_STRESS_MODE", "strict-lifecycle");
const profile = env("DEV_STRESS_PROFILE", "default");
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
        await runStressStep({
          runtimeUrl,
          duration,
          workers: String(workerCount),
          rate,
          rateSchedule,
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
        rateSchedule,
        mode,
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
  return { submit: "70", modify: "20", cancel: "10" };
}

async function runStressStep({ runtimeUrl, duration, workers, rate, rateSchedule, mode, traceLimit, actionMix, reportOut }) {
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
      "--rate-schedule",
      rateSchedule,
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
    { name: "runtime.hotPath", url: `${runtimeUrl}/internal/perf/hot-path`, captureJson: true },
    { name: "runtime.dbPools", url: `${runtimeUrl}/internal/perf/db-pools`, captureJson: true },
    { name: "runtime.asyncCommands", url: `${runtimeUrl}/internal/commands/async/stats`, captureJson: true },
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
