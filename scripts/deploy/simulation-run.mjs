#!/usr/bin/env node
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const doHarness = resolve(repoRoot, "scripts/dev/do-benchmark-host.sh");

const args = process.argv.slice(2);
const command = args[0] && !args[0].startsWith("--") ? args.shift() : "run";

if (command === "help" || command === "--help" || command === "-h" || args.includes("--help") || args.includes("-h")) {
  usage();
  process.exit(0);
}

const options = parseArgs(args);

if (command !== "run" && command !== "check" && command !== "push-artifacts") {
  console.error(`Unknown command: ${command}`);
  usage();
  process.exit(2);
}

const runId = option("run-id", env("REEF_SIM_RUN_ID", `sim-do-${timestamp()}`));
const reportRoot = resolve(repoRoot, option("report-root", env("REEF_SIM_REPORT_ROOT", "reports/simulations/ephemeral-do")));
const reportDir = resolve(reportRoot, runId);
const profile = option("profile", env("REEF_SIM_PROFILE", "stream-ack"));
if (profile !== "stream-ack" && profile !== "materializer" && profile !== "arena") {
  console.error(`Invalid profile: ${profile}. Expected stream-ack, materializer, or arena.`);
  process.exit(2);
}
const imageMode = option("image-mode", env("REEF_SIM_IMAGE_MODE", profile === "arena" ? "source" : "dockerhub"));
if (imageMode !== "dockerhub" && imageMode !== "source") {
  console.error(`Invalid image mode: ${imageMode}. Expected dockerhub or source.`);
  process.exit(2);
}
const goal = option("goal", env("REEF_SIM_GOAL", ""));
const targetRps = option("target-rps", env("REEF_SIM_TARGET_RPS", ""));
const targetP95Ms = option("target-p95-ms", env("REEF_SIM_TARGET_P95_MS", ""));
const targetP99Ms = option("target-p99-ms", env("REEF_SIM_TARGET_P99_MS", ""));
const size = option("size", env("REEF_SIM_SIZE", ""));
const rate = option("rate", env("RATE", env("REEF_SIM_RATE", "")));
const duration = option("duration", env("DURATION", env("REEF_SIM_DURATION", "")));
const workers = option("workers", env("WORKERS", env("REEF_SIM_WORKERS", "")));
const minRps = option("min-rps", env("REEF_SIM_MIN_RPS", defaultMinRps(rate)));
const keepWorkerOnFailure = boolOption("keep-worker-on-failure", env("REEF_SIM_KEEP_WORKER_ON_FAILURE", "0"));
const skipDestroy = boolOption("keep-worker", env("REEF_SIM_KEEP_WORKER", "0"));
const exportToR2 = boolOption("export-to-r2", env("REEF_DO_EXPORT_TO_R2", "0"));

const commonEnv = {
  ...process.env,
  REEF_DO_BENCHMARK_PROFILE: profile,
  REEF_DO_RUN_ID: runId,
  REEF_DO_LOCAL_REPORT_ROOT: reportRoot,
};
if (exportToR2) commonEnv.REEF_DO_EXPORT_TO_R2 = "1";
if (goal) commonEnv.REEF_DO_BENCHMARK_GOAL = goal;
if (targetRps) commonEnv.REEF_DO_TARGET_ACCEPTED_RPS = targetRps;
if (targetP95Ms) commonEnv.REEF_DO_TARGET_P95_MS = targetP95Ms;
if (targetP99Ms) commonEnv.REEF_DO_TARGET_P99_MS = targetP99Ms;
if (size) commonEnv.REEF_DO_SIZE = size;
if (rate) commonEnv.REEF_DO_STRESS_RATES = rate;
if (duration) commonEnv.REEF_DO_STRESS_DURATION = duration;
if (workers) commonEnv.REEF_DO_STRESS_WORKERS = workers;
if (minRps) {
  commonEnv.REEF_DO_MIN_ATTEMPTED_RPS = minRps;
  commonEnv.REEF_DO_MIN_ACCEPTED_RPS = minRps;
}
commonEnv.REEF_DO_IMAGE_MODE = imageMode;

mkdirSync(reportDir, { recursive: true });
writeMetadata("started", {
  profile,
  imageMode,
  goal,
  targetRps,
  targetP95Ms,
  targetP99Ms,
  size,
  rate,
  duration,
  workers,
  minRps,
  keepWorkerOnFailure,
  skipDestroy,
});

if (command === "check") {
  const status = run(doHarness, ["check"], { env: commonEnv });
  writeMetadata(status === 0 ? "check_passed" : "check_failed", {
    profile,
    imageMode,
    goal,
    targetRps,
    targetP95Ms,
    targetP99Ms,
    size,
    rate,
    duration,
    workers,
    minRps,
    commandStatus: status,
  });
  process.exit(status);
}

if (command === "push-artifacts") {
  const status = pushArtifacts();
  writeMetadata(status === 0 ? "artifacts_pushed" : "artifact_push_failed", {
    profile,
    imageMode,
    goal,
    targetRps,
    targetP95Ms,
    targetP99Ms,
    size,
    rate,
    duration,
    workers,
    minRps,
    commandStatus: status,
  });
  process.exit(status);
}

console.log(`ephemeral simulation run_id=${runId}`);
console.log(`profile=${profile}`);
console.log(`image_mode=${imageMode}`);
console.log(`reports=${reportDir}`);
console.log("worker lifecycle=provision -> run -> fetch artifacts -> push optional core copy -> destroy unless retained");

let runStatus = run(doHarness, ["run"], { env: commonEnv });
let pushStatus = pushArtifacts();
let destroyStatus = 0;

const shouldDestroy = !skipDestroy && (runStatus === 0 || !keepWorkerOnFailure);
if (shouldDestroy) {
  destroyStatus = run(doHarness, ["destroy"], { env: commonEnv, allowFailure: true });
} else {
  console.warn("worker retained; remember this continues billing until destroyed");
}

const finalStatus = firstNonZero(runStatus, pushStatus, destroyStatus);
writeMetadata(finalStatus === 0 ? "completed" : "failed", {
  profile,
  imageMode,
  goal,
  targetRps,
  targetP95Ms,
  targetP99Ms,
  size,
  rate,
  duration,
  workers,
  minRps,
  runStatus,
  pushStatus,
  destroyStatus,
  workerDestroyed: shouldDestroy && destroyStatus === 0,
  workerRetained: !shouldDestroy,
});
process.exit(finalStatus);

function usage() {
  console.log(`Usage: scripts/deploy/simulation-run.mjs [run|check|push-artifacts] [options]

Local-driven ephemeral simulation runner. It uses the existing DigitalOcean
OpenTofu harness for compute and keeps run artifacts under reports/simulations.

Options:
  --run-id <id>                 stable run id; default sim-do-<UTC timestamp>
  --rate <rps[,rps]>            stress rates; maps to REEF_DO_STRESS_RATES
  --duration <duration>         stress duration; maps to REEF_DO_STRESS_DURATION
  --workers <count>             stress workers; maps to REEF_DO_STRESS_WORKERS
  --min-rps <rps>               attempted/accepted rps gate; default 90% of the lowest requested rate
  --profile <name>              benchmark profile: stream-ack, materializer, or arena; default stream-ack
  --goal <name>                 fixed, latency-knee, sustain, or ceiling; maps to REEF_DO_BENCHMARK_GOAL
  --target-rps <rps>            accepted-rps target; maps to REEF_DO_TARGET_ACCEPTED_RPS
  --target-p95-ms <ms>          p95 report gate; maps to REEF_DO_TARGET_P95_MS
  --target-p99-ms <ms>          p99 report gate; maps to REEF_DO_TARGET_P99_MS
  --size <slug>                 DO size override; maps to REEF_DO_SIZE
  --image-mode <dockerhub|source>
                                  runtime image mode; default dockerhub
  --report-root <path>          local artifact root; default reports/simulations/ephemeral-do
  --keep-worker-on-failure      leave DO worker up when the run fails
  --keep-worker                 leave DO worker up even after success
  --export-to-r2                compress artifacts and upload to R2 from the worker before fetch/destroy

Required for provisioning:
  DIGITALOCEAN_TOKEN or DO_TOKEN
  REEF_DO_CONFIRM_DESTROYABLE=1

Optional core artifact push:
  REEF_CORE_REPORT_HOST=core.example.com
  REEF_CORE_REPORT_USER=ops
  REEF_CORE_REPORT_DIR=/opt/reef/reports/simulations

Optional R2 export (--export-to-r2), reusing the backbone's backup bucket credentials:
  R2_ENDPOINT=https://<account-id>.r2.cloudflarestorage.com
  R2_BUCKET=reef-backups
  AWS_ACCESS_KEY_ID=...
  AWS_SECRET_ACCESS_KEY=...
`);
}

function parseArgs(rawArgs) {
  const parsed = new Map();
  for (let index = 0; index < rawArgs.length; index += 1) {
    const arg = rawArgs[index];
    if (!arg.startsWith("--")) {
      console.error(`Unexpected argument: ${arg}`);
      process.exit(2);
    }
    const key = arg.slice(2);
    if (key === "keep-worker-on-failure" || key === "keep-worker" || key === "export-to-r2") {
      parsed.set(key, "1");
      continue;
    }
    const value = rawArgs[index + 1];
    if (!value || value.startsWith("--")) {
      console.error(`Missing value for --${key}`);
      process.exit(2);
    }
    parsed.set(key, value);
    index += 1;
  }
  return parsed;
}

function option(name, fallback = "") {
  return options.get(name) || fallback;
}

function boolOption(name, fallback = "0") {
  return option(name, fallback) === "1" || option(name, fallback).toLowerCase() === "true";
}

function env(name, fallback = "") {
  const value = process.env[name];
  return value == null || value === "" ? fallback : value;
}

function defaultMinRps(rateList) {
  if (!rateList) return "";
  const rates = rateList
    .split(",")
    .map((value) => Number(value.trim()))
    .filter((value) => Number.isFinite(value) && value > 0);
  if (rates.length === 0) return "";
  return String(Math.floor(Math.min(...rates) * 0.9));
}

function run(commandPath, commandArgs, { env: childEnv, allowFailure = false } = {}) {
  const result = spawnSync(commandPath, commandArgs, {
    cwd: repoRoot,
    stdio: "inherit",
    env: childEnv || process.env,
  });
  const status = result.status ?? 1;
  if (status !== 0 && !allowFailure) {
    return status;
  }
  return status;
}

function pushArtifacts() {
  const host = env("REEF_CORE_REPORT_HOST");
  if (!host) {
    console.log("REEF_CORE_REPORT_HOST is unset; keeping artifacts locally only");
    return 0;
  }
  const user = env("REEF_CORE_REPORT_USER", "ops");
  const remoteDir = env("REEF_CORE_REPORT_DIR", "/opt/reef/reports/simulations");
  const target = `${user}@${host}:${remoteDir}/${runId}/`;
  console.log(`pushing artifacts to ${target}`);
  return run("rsync", ["-az", "--delete", `${reportDir}/`, target], { allowFailure: true });
}

function writeMetadata(status, data) {
  try {
    mkdirSync(reportDir, { recursive: true });
    writeFileSync(
      resolve(reportDir, "simulation-run-metadata.json"),
      `${JSON.stringify(
        {
          runId,
          status,
          createdAt: new Date().toISOString(),
          provider: "digitalocean",
          workerKind: "ephemeral",
          localReportDir: reportDir,
          ...data,
        },
        null,
        2,
      )}\n`,
    );
  } catch (error) {
    console.warn(`warning: unable to write simulation metadata: ${error.message}`);
  }
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}

function firstNonZero(...codes) {
  return codes.find((code) => code !== 0) || 0;
}
