import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { basename, resolve } from "node:path";
import { evaluateReportDrift } from "./lib/scenario-drift.mjs";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();
const { runtimeUrl } = deriveDevUrls();

const baselinePath = env(
  "DEV_REPLAY_BASELINE",
  "services/simulator/replay/golden/persona-session.baseline.json",
);
const artifactDir = resolve(env("DEV_REPLAY_ARTIFACT_DIR", "/tmp/reef-replay-pack"));
const duration = env("DEV_REPLAY_DURATION", "20s");
const workers = env("DEV_REPLAY_WORKERS", "8");
const rate = env("DEV_REPLAY_RATE", "120");
const traceLimit = env("DEV_REPLAY_TRACE_CHECK_LIMIT", "100");

const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
const sessionConfigPath = env("DEV_REPLAY_SESSION_CONFIG", baseline.sessionConfig);
mkdirSync(artifactDir, { recursive: true });

const artifactBaseName = basename(sessionConfigPath).replace(/\.(yaml|yml|json)$/i, "");
const reportOut = resolve(artifactDir, `${artifactBaseName}.report.json`);
const checkOut = resolve(artifactDir, `${artifactBaseName}.check.json`);

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
const check = evaluateReportDrift(report, baseline, { baselinePath, reportPath: reportOut });
writeFileSync(checkOut, JSON.stringify(check, null, 2));

for (const warning of check.warnings ?? []) {
  console.warn(`replay pack drift warning: ${warning}`);
}
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
