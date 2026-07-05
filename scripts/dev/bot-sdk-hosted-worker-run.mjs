import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { basename, isAbsolute, resolve } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);
const artifactPathArg = args.find((arg) => !arg.startsWith("--"));
const fixturePathArg = args.filter((arg) => !arg.startsWith("--"))[1] ?? "packages/bot-sdk/fixtures/aapl-multi-tick.json";
const wallTimeoutMs = numberOption("--wall-timeout-ms", 5000);
const maxOutputBytes = numberOption("--max-output-bytes", 1024 * 1024);
const tickTimeoutMs = optionalNumberOption("--tick-timeout-ms");
const lifecycleTimeoutMs = optionalNumberOption("--lifecycle-timeout-ms");

if (!artifactPathArg) {
  console.error("usage: bun scripts/dev/bot-sdk-hosted-worker-run.mjs <compiled-bot.js> [fixture.json] [--wall-timeout-ms=5000] [--max-output-bytes=1048576]");
  process.exit(2);
}

const artifactPath = isAbsolute(artifactPathArg) ? artifactPathArg : resolve(repoRoot, artifactPathArg);
const fixturePath = isAbsolute(fixturePathArg) ? fixturePathArg : resolve(repoRoot, fixturePathArg);
const payload = {
  source: readFileSync(artifactPath, "utf8"),
  fileName: basename(artifactPath),
  fixture: JSON.parse(readFileSync(fixturePath, "utf8")),
  executionLimits: {
    ...(tickTimeoutMs === undefined ? {} : { tickTimeoutMs }),
    ...(lifecycleTimeoutMs === undefined ? {} : { lifecycleTimeoutMs }),
  },
};
if (Object.keys(payload.executionLimits).length === 0) {
  delete payload.executionLimits;
}

const report = await runHostedWorker(payload);
console.log(JSON.stringify(report, null, 2));
if (report.status !== "completed") {
  process.exit(1);
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
      finish({
        status: "do_not_merge",
        scenarioId: payloadValue.fixture.scenarioId,
        runId: payloadValue.fixture.runId,
        ticksRun: 0,
        actionsProposed: 0,
        orderActionsProposed: 0,
        dataCalls: 0,
        issues: [{ code: "hosted_worker_timeout", message: `Hosted worker exceeded wall timeout ${wallTimeoutMs}ms.` }],
        denials: [],
        logs: [],
        ticks: [],
        finalOrders: [],
      });
      child.kill("SIGKILL");
    }, wallTimeoutMs);

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => appendOutput("stdout", chunk));
    child.stderr.on("data", (chunk) => appendOutput("stderr", chunk));
    child.on("error", (error) => finish(errorReport(payloadValue, "hosted_worker_spawn_failed", error.message)));
    child.on("close", (code) => {
      if (settled) return;
      clearTimeout(timeout);
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

    function finish(report) {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      resolve(report);
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
    issues: [{ code, message }],
    denials: [],
    logs: [],
    ticks: [],
    finalOrders: [],
  };
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
