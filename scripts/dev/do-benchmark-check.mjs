import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const target = process.argv[2];
if (!target) {
  console.error("usage: node scripts/dev/do-benchmark-check.mjs <artifact-dir>");
  process.exit(2);
}

const requiredRates = parseCsvInts(process.env.REEF_DO_REQUIRED_RATES || "2500,5000");
const allowBackpressure429 = process.env.REEF_DO_ALLOW_429 === "1";
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
  const match = reports.find(({ report }) => Number(report.config?.ratePerSecond) === rate);
  if (!match) {
    failures.push(`missing measured stress report for rate ${rate}`);
    continue;
  }
  validateReport(match.path, match.report);
}

validateTelemetry(target);

finish();

function validateReport(path, report) {
  const label = `${path} rate=${report.config?.ratePerSecond ?? "unknown"}`;
  const total = Number(report.totalRequests ?? 0);
  const success = Number(report.totalSuccess ?? 0);
  const failuresCount = Number(report.totalFailures ?? 0);
  if (total <= 0) {
    failures.push(`${label}: totalRequests must be > 0`);
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
  if (traceChecked > 0 && tracePass !== traceChecked) {
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
    if (Number(projectorDelta.projectedDelta ?? 0) <= 0) {
      failures.push(`${label}: streamAckProjector.delta.projectedDelta must be > 0`);
    }
    if (!Number.isFinite(Number(projectorDelta.afterLag))) {
      failures.push(`${label}: streamAckProjector.delta.afterLag must be numeric`);
    }
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

function isMeasuredStressReport(json) {
  return Boolean(
    json &&
      typeof json === "object" &&
      json.config &&
      Number.isFinite(Number(json.config.ratePerSecond)) &&
      Number.isFinite(Number(json.totalRequests)),
  );
}

function normalizeStatusCodes(raw) {
  return new Map(
    Object.entries(raw).map(([key, value]) => [Number(key), Number(value ?? 0)]),
  );
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
