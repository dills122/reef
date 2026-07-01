import { basename, join } from "node:path";
import { mkdirSync } from "node:fs";
import { deriveDevUrls, env, loadDotEnv, run } from "./lib/dev-utils.mjs";

loadDotEnv();

const { runtimeUrl } = deriveDevUrls();
const duration = env("DEV_INTAKE_DURATION", "30s");
const workers = env("DEV_INTAKE_WORKERS", "256");
const rate = env("DEV_INTAKE_RATE", "10000");
const rateSchedule = env("DEV_INTAKE_RATE_SCHEDULE", "precise");
const actorIdPrefix = env("DEV_INTAKE_ACTOR_ID_PREFIX", "bot");
const artifactDir = env("DEV_INTAKE_ARTIFACT_DIR", "/tmp");
const out = env("DEV_INTAKE_REPORT_OUT", "/tmp/reef-intake-bench.json");
const baseOut = out.replace(/\.json$/, "");
const reportBaseName = basename(baseOut);
const reportOut = join(artifactDir, `${reportBaseName}-workers-${workers}-rate-${rate}.json`);

mkdirSync(artifactDir, { recursive: true });

console.log(`running intake bench against ${runtimeUrl}`);
console.log(`  duration=${duration} workers=${workers} rate=${rate} schedule=${rateSchedule} actorPrefix=${actorIdPrefix}`);

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

console.log("intake bench complete. report:");
console.log(`  ${reportOut}`);
