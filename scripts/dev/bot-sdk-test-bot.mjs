import { spawn, spawnSync } from "node:child_process";
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
const summaryOnly = args.includes("--summary-only");

if (!botPathArg) {
  console.error(
    "usage: bun scripts/dev/bot-sdk-test-bot.mjs <bot-file.ts> [fixture.json] [--summary-only] [--tick-timeout-ms=1000] [--lifecycle-timeout-ms=1000] [--wall-timeout-ms=5000] [--max-output-bytes=1048576]",
  );
  process.exit(2);
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
const report = await runHostedWorker({
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

function runHostedWorker(payloadValue) {
  return new Promise((resolve) => {
    const child = spawn("bun", ["scripts/dev/bot-sdk-hosted-worker-child.mjs"], {
      cwd: repoRoot,
      stdio: ["pipe", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let outputBytes = 0;
    let settled = false;
    const timeout = setTimeout(() => {
      finish(errorReport(payloadValue, "hosted_worker_timeout", `Hosted worker exceeded wall timeout ${wallTimeoutMs}ms.`));
      child.kill("SIGKILL");
    }, wallTimeoutMs);

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => appendOutput("stdout", chunk));
    child.stderr.on("data", (chunk) => appendOutput("stderr", chunk));
    child.on("error", (error) => finish(errorReport(payloadValue, "hosted_worker_spawn_failed", error.message)));
    child.on("close", (code) => {
      if (settled) return;
      const parsed = parseLastJsonLine(stdout);
      if (parsed !== undefined) {
        finish(parsed);
        return;
      }
      finish(errorReport(payloadValue, "hosted_worker_failed", `Hosted worker exited with code ${code ?? "unknown"}: ${stderr || stdout}`));
    });
    child.stdin.end(JSON.stringify(payloadValue));

    function appendOutput(stream, chunk) {
      outputBytes += Buffer.byteLength(chunk);
      if (outputBytes > maxOutputBytes) {
        finish(errorReport(payloadValue, "hosted_worker_output_limit_exceeded", `Hosted worker exceeded output limit ${maxOutputBytes} bytes.`));
        child.kill("SIGKILL");
        return;
      }
      if (stream === "stdout") stdout += chunk;
      else stderr += chunk;
    }

    function finish(reportValue) {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve(reportValue);
    }
  });
}

function parseLastJsonLine(output) {
  for (const line of output.trim().split(/\r?\n/).reverse()) {
    if (!line.trim().startsWith("{")) continue;
    try {
      return JSON.parse(line);
    } catch {
      return undefined;
    }
  }
  return undefined;
}

function errorReport(payloadValue, code, message) {
  return {
    status: "do_not_merge",
    scenarioId: payloadValue.fixture.scenarioId,
    runId: payloadValue.fixture.runId,
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
