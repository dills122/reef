import { basename, join } from "node:path";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
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
const captureDbDiagnostics = env("DEV_INTAKE_CAPTURE_DB_DIAGNOSTICS", "1") !== "0";
const dbDiagnosticsService = env("DEV_INTAKE_DB_SERVICE", "postgres");
const dbDiagnosticsUser = env("DEV_INTAKE_DB_USER", "reef");
const dbDiagnosticsName = env("DEV_INTAKE_DB_NAME", "reef");
const dbDiagnosticsSchemas = parseCsv(env("DEV_INTAKE_DB_SCHEMAS", defaultDiagnosticSchemas.join(",")));
const baseOut = out.replace(/\.json$/, "");
const reportBaseName = basename(baseOut);
const reportOut = join(artifactDir, `${reportBaseName}-workers-${workers}-rate-${rate}.json`);
const diagnosticsDir = join(artifactDir, `${reportBaseName}-db-diagnostics-workers-${workers}-rate-${rate}`);

mkdirSync(artifactDir, { recursive: true });

console.log(`running intake bench against ${runtimeUrl}`);
console.log(`  duration=${duration} workers=${workers} rate=${rate} schedule=${rateSchedule} actorPrefix=${actorIdPrefix}`);

let preDbDiagnostics = null;
let postDbDiagnostics = null;
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
      "--pretty-summary",
      "--report-out",
      reportOut,
    ],
    { cwd: "services/simulator" },
  );
} finally {
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
