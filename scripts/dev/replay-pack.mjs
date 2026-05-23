import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();

const baselinePath = env(
  "DEV_REPLAY_BASELINE",
  "services/simulator/replay/golden/persona-session.baseline.json",
);
const artifactDir = env("DEV_REPLAY_ARTIFACT_DIR", "/tmp/reef-replay-pack");
const duration = env("DEV_REPLAY_DURATION", "20s");
const workers = env("DEV_REPLAY_WORKERS", "8");
const rate = env("DEV_REPLAY_RATE", "120");
const traceLimit = env("DEV_REPLAY_TRACE_CHECK_LIMIT", "100");

const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
const sessionConfigPath = env("DEV_REPLAY_SESSION_CONFIG", baseline.sessionConfig);
mkdirSync(artifactDir, { recursive: true });

const reportOut = join(artifactDir, `${basename(sessionConfigPath).replace(/\.(yaml|yml|json)$/i, "")}.report.json`);
const checkOut = join(artifactDir, `${basename(sessionConfigPath).replace(/\.(yaml|yml|json)$/i, "")}.check.json`);

console.log(`running replay pack for ${sessionConfigPath}`);
await run(
  "go",
  [
    "run",
    "./cmd/load-tester",
    "--base-url",
    runtimeUrl,
    "--session-config",
    sessionConfigPath,
    "--duration",
    duration,
    "--workers",
    workers,
    "--rate",
    rate,
    "--trace-check-limit",
    traceLimit,
    "--report-out",
    reportOut,
    "--pretty-summary",
  ],
  { cwd: "services/simulator" },
);

const report = JSON.parse(readFileSync(reportOut, "utf8"));
const check = evaluateReport(report, baseline);
writeFileSync(checkOut, JSON.stringify(check, null, 2));

if (!check.pass) {
  console.error("replay pack drift check failed");
  for (const failure of check.failures) {
    console.error(`  - ${failure}`);
  }
  process.exitCode = 1;
} else {
  console.log("replay pack drift check passed");
}
console.log(`report: ${reportOut}`);
console.log(`check : ${checkOut}`);

function evaluateReport(report, baseline) {
  const failures = [];
  const t = baseline.thresholds ?? {};

  if (Number(report.throughputRps ?? 0) < Number(t.minThroughputRps ?? 0)) {
    failures.push(`throughputRps ${report.throughputRps} < ${t.minThroughputRps}`);
  }
  if (Number(report.acceptedBusinessOpsRps ?? 0) < Number(t.minAcceptedBusinessOpsRps ?? 0)) {
    failures.push(
      `acceptedBusinessOpsRps ${report.acceptedBusinessOpsRps} < ${t.minAcceptedBusinessOpsRps}`,
    );
  }
  if (Number(report.latencyMs?.p95 ?? 0) > Number(t.maxP95LatencyMs ?? Number.POSITIVE_INFINITY)) {
    failures.push(`latencyMs.p95 ${report.latencyMs?.p95} > ${t.maxP95LatencyMs}`);
  }
  const traceChecked = Number(report.traceChecks?.checked ?? 0);
  const tracePass = Number(report.traceChecks?.pass ?? 0);
  const tracePassRate = traceChecked > 0 ? (tracePass / traceChecked) * 100 : 0;
  if (tracePassRate < Number(t.minTracePassRatePct ?? 0)) {
    failures.push(`trace pass rate ${tracePassRate.toFixed(2)}% < ${t.minTracePassRatePct}%`);
  }

  const requiredAttribution = baseline.requiredAttribution ?? [];
  for (const key of requiredAttribution) {
    const bucket = report[key];
    if (!bucket || Object.keys(bucket).length === 0) {
      failures.push(`missing or empty attribution bucket: ${key}`);
    }
  }

  const presentRejectCodes = new Set((report.rejectTaxonomy ?? []).map((row) => row.code));
  for (const code of baseline.requiredRejectCodes ?? []) {
    if (!presentRejectCodes.has(code)) {
      failures.push(`missing reject taxonomy code: ${code}`);
    }
  }

  return {
    pass: failures.length === 0,
    baselinePath,
    sessionConfigPath,
    evaluatedAt: new Date().toISOString(),
    metrics: {
      throughputRps: report.throughputRps,
      acceptedBusinessOpsRps: report.acceptedBusinessOpsRps,
      p95LatencyMs: report.latencyMs?.p95,
      tracePassRatePct: tracePassRate,
    },
    failures,
  };
}
