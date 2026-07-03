import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const target = process.argv[2];
if (!target) {
  console.error("usage: node scripts/dev/do-benchmark-check.mjs <artifact-dir>");
  process.exit(2);
}

const requiredRates = parseCsvInts(process.env.REEF_DO_REQUIRED_RATES || "2500,5000");
const allowBackpressure429 = process.env.REEF_DO_ALLOW_429 === "1";
const requireTraceChecks = process.env.REEF_DO_REQUIRE_TRACE_CHECKS === "1";
const minAttemptedRps = parseOptionalNumber(process.env.REEF_DO_MIN_ATTEMPTED_RPS);
const minAcceptedRps = parseOptionalNumber(process.env.REEF_DO_MIN_ACCEPTED_RPS);
const minWorkerCompletedRps = parseOptionalNumber(process.env.REEF_DO_MIN_WORKER_COMPLETED_RPS);
const failures = [];

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

for (const rate of requiredRates) {
  const match = reports.find(({ report }) => reportRatePerSecond(report) === rate);
  if (!match) {
    failures.push(`missing measured stress report for rate ${rate}`);
    continue;
  }
  validateReport(match.path, match.report);
}

validateTelemetry(target);

finish();

function validateReport(path, report) {
  const label = `${path} rate=${reportRatePerSecond(report) ?? "unknown"}`;
  const total = Number(report.totalRequests ?? 0);
  const success = Number(report.totalSuccess ?? 0);
  const failuresCount = Number(report.totalFailures ?? 0);
  const attemptedRps = reportMetric(report, "attemptedCommandsPerSecond", "throughputRps");
  const acceptedRps = reportMetric(report, "acceptedCommandsPerSecond", "acceptedBusinessOpsRps");
  const workerCompletedRps = reportMetric(report, "workerCompletedCommandsPerSecond");
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

function parseOptionalNumber(raw) {
  if (raw === undefined || String(raw).trim() === "") return undefined;
  const numeric = Number(String(raw).trim());
  return Number.isFinite(numeric) && numeric > 0 ? numeric : undefined;
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
  if (failures.length > 0) {
    console.error("DO benchmark report gates failed:");
    for (const failure of failures) {
      console.error(`  - ${failure}`);
    }
    process.exit(1);
  }
  console.log(`DO benchmark report gates passed for ${target}`);
}
