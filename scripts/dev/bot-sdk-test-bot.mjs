import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, isAbsolute, join, resolve } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);
const botPathArg = args.find((arg) => !arg.startsWith("--"));
const fixturePathArg = args.filter((arg) => !arg.startsWith("--"))[1] ?? "packages/bot-sdk/fixtures/aapl-multi-tick.json";
const tickTimeoutMs = optionalNumberOption("--tick-timeout-ms");
const lifecycleTimeoutMs = optionalNumberOption("--lifecycle-timeout-ms");
const wallTimeoutMs = numberOption("--wall-timeout-ms", 5000);
const maxOutputBytes = numberOption("--max-output-bytes", 1024 * 1024);
const isolation = optionValue("--isolation") ?? "worker";
const summaryOnly = args.includes("--summary-only");

if (!botPathArg) {
  console.error(
    "usage: bun scripts/dev/bot-sdk-test-bot.mjs <bot-file.ts> [fixture.json] [--summary-only] [--isolation=worker|container] [--tick-timeout-ms=1000] [--lifecycle-timeout-ms=1000] [--wall-timeout-ms=5000] [--max-output-bytes=1048576]",
  );
  process.exit(2);
}
if (!["worker", "container"].includes(isolation)) {
  throw new Error(`--isolation must be worker or container; got ${isolation}`);
}

const botPath = isAbsolute(botPathArg) ? botPathArg : resolve(repoRoot, botPathArg);
const fixturePath = isAbsolute(fixturePathArg) ? fixturePathArg : resolve(repoRoot, fixturePathArg);
const outDir = mkdtempSync(join(tmpdir(), "reef-bot-sdk-test-"));
const artifactPath = join(outDir, `${basename(botPath, ".ts")}.bundle.js`);
const manifestPath = join(outDir, `${basename(botPath, ".ts")}.bundle.manifest.json`);

const build = spawnSync(
  "bun",
  [
    "scripts/dev/bot-sdk-build-hosted-artifact.mjs",
    botPath,
    `--out=${artifactPath}`,
    `--manifest-out=${manifestPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

if (build.error || build.status !== 0) {
  const buildMessage =
    build.error?.message ||
    build.stderr?.trim() ||
    build.stdout?.trim() ||
    "Hosted artifact build failed.";
  const report = {
    approvalStatus: "do_not_merge",
    phase: "artifact_build",
    botFile: relativeToRepo(botPath),
    fixture: relativeToRepo(fixturePath),
    issues: [
      {
        code: "hosted_artifact_build_failed",
        message: buildMessage,
      },
    ],
  };
  console.log(JSON.stringify(report, null, 2));
  process.exit(1);
}

const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const report = runHostedArtifact(artifactPath, fixturePath);

const summary = {
  approvalStatus: report.status === "completed" ? "approved_for_merge" : "do_not_merge",
  phase: "hosted_simulation",
  botFile: relativeToRepo(botPath),
  fixture: relativeToRepo(fixturePath),
  artifact: {
    sourceHash: manifest.sourceHash,
    artifactHash: manifest.artifactHash,
    approvedPackages: manifest.approvedPackages ?? [],
    isolation,
  },
  scenarioId: report.scenarioId,
  runId: report.runId,
  ticksRun: report.ticksRun,
  actionsProposed: report.actionsProposed,
  orderActionsProposed: report.orderActionsProposed,
  dataCalls: report.dataCalls,
  signalsGenerated: report.signalsGenerated ?? 0,
  eventsProcessed: report.eventsProcessed ?? 0,
  issues: report.issues,
  denials: report.denials,
};

console.log(JSON.stringify(summaryOnly ? summary : { ...summary, report }, null, 2));

if (summary.approvalStatus !== "approved_for_merge") {
  process.exit(1);
}

function optionalNumberOption(name) {
  const raw = optionValue(name);
  if (raw === undefined) return undefined;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be numeric; got ${raw}`);
  }
  return parsed;
}

function numberOption(name, fallback) {
  const raw = optionValue(name);
  if (raw === undefined) return fallback;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} must be numeric; got ${raw}`);
  }
  return parsed;
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}

function relativeToRepo(pathValue) {
  return pathValue.startsWith(repoRoot) ? pathValue.slice(repoRoot.length) : pathValue;
}

function runHostedArtifact(artifactPathValue, fixturePathValue) {
  const run = spawnSync(
    "bun",
    [
      "scripts/dev/bot-sdk-hosted-worker-run.mjs",
      artifactPathValue,
      fixturePathValue,
      `--isolation=${isolation}`,
      `--wall-timeout-ms=${wallTimeoutMs}`,
      `--max-output-bytes=${maxOutputBytes}`,
      ...(tickTimeoutMs === undefined ? [] : [`--tick-timeout-ms=${tickTimeoutMs}`]),
      ...(lifecycleTimeoutMs === undefined ? [] : [`--lifecycle-timeout-ms=${lifecycleTimeoutMs}`]),
    ],
    { cwd: repoRoot, encoding: "utf8" },
  );
  const parsed = parseJson(run.stdout);
  if (parsed !== undefined) {
    return parsed;
  }
  return errorReport(
    fixture,
    "hosted_worker_failed",
    run.error?.message || run.stderr?.trim() || run.stdout?.trim() || `hosted worker exited with ${run.status ?? "unknown"}`,
  );
}

function parseJson(output) {
  if (output.trim().length === 0) return undefined;
  try {
    return JSON.parse(output);
  } catch {
    return undefined;
  }
}

function errorReport(fixtureValue, code, message) {
  return {
    status: "do_not_merge",
    scenarioId: fixtureValue.scenarioId,
    runId: fixtureValue.runId,
    ticksRun: 0,
    actionsProposed: 0,
    orderActionsProposed: 0,
    dataCalls: 0,
    signalsGenerated: 0,
    eventsProcessed: 0,
    issues: [{ code, message }],
    denials: [],
    logs: [],
    ticks: [],
    finalOrders: [],
  };
}
