import "ses";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, isAbsolute, join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);
const botPathArg = args.find((arg) => !arg.startsWith("--"));
const fixturePathArg = args.filter((arg) => !arg.startsWith("--"))[1] ?? "packages/bot-sdk/fixtures/aapl-multi-tick.json";
const tickTimeoutMs = optionalNumberOption("--tick-timeout-ms");
const lifecycleTimeoutMs = optionalNumberOption("--lifecycle-timeout-ms");
const summaryOnly = args.includes("--summary-only");

if (!botPathArg) {
  console.error(
    "usage: bun scripts/dev/bot-sdk-test-bot.mjs <bot-file.ts> [fixture.json] [--summary-only] [--tick-timeout-ms=1000] [--lifecycle-timeout-ms=1000]",
  );
  process.exit(2);
}

const botPath = isAbsolute(botPathArg) ? botPathArg : resolve(repoRoot, botPathArg);
const fixturePath = isAbsolute(fixturePathArg) ? fixturePathArg : resolve(repoRoot, fixturePathArg);
const outDir = mkdtempSync(join(tmpdir(), "reef-bot-sdk-test-"));
const artifactPath = join(outDir, `${basename(botPath, ".ts")}.bundle.js`);
const manifestPath = join(outDir, `${basename(botPath, ".ts")}.bundle.manifest.json`);
const hostedRunner = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/hosted-runner.ts")).href);

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

if (build.status !== 0) {
  const report = {
    approvalStatus: "do_not_merge",
    phase: "artifact_build",
    botFile: relativeToRepo(botPath),
    fixture: relativeToRepo(fixturePath),
    issues: [
      {
        code: "hosted_artifact_build_failed",
        message: build.stderr.trim() || build.stdout.trim() || "Hosted artifact build failed.",
      },
    ],
  };
  console.log(JSON.stringify(report, null, 2));
  process.exit(1);
}

const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const source = readFileSync(artifactPath, "utf8");
const executionLimits = {
  ...(tickTimeoutMs === undefined ? {} : { tickTimeoutMs }),
  ...(lifecycleTimeoutMs === undefined ? {} : { lifecycleTimeoutMs }),
};
const report = await hostedRunner.runHostedBotScenarioV1({
  source,
  fileName: basename(artifactPath),
  fixture: {
    ...fixture,
    botId: fixture.botId ?? basename(botPath, ".ts"),
  },
  ...(Object.keys(executionLimits).length === 0 ? {} : { executionLimits }),
});

const summary = {
  approvalStatus: report.status === "completed" ? "approved_for_merge" : "do_not_merge",
  phase: "hosted_simulation",
  botFile: relativeToRepo(botPath),
  fixture: relativeToRepo(fixturePath),
  artifact: {
    sourceHash: manifest.sourceHash,
    artifactHash: manifest.artifactHash,
    approvedPackages: manifest.approvedPackages ?? [],
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

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}

function relativeToRepo(pathValue) {
  return pathValue.startsWith(repoRoot) ? pathValue.slice(repoRoot.length) : pathValue;
}
