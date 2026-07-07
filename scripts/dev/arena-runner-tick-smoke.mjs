import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);
const config = {
  bots: csvOption("--bots", "simple,lifecycle,refreshing"),
  compartment: stringOption("--compartment", "vm"),
  out: stringOption("--out", "/tmp/reef-arena-runner-tick-smoke.json"),
};

if (!["vm", "ses"].includes(config.compartment)) {
  throw new Error(`unsupported --compartment=${config.compartment}; expected vm or ses`);
}

const baseFixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const outDir = mkdtempSync(join(tmpdir(), "reef-arena-runner-tick-"));
const worker = new RunnerWorker("tick-worker-0", config.compartment);
const cases = config.bots.map((id) => benchCase(id));
const artifacts = cases.map((testCase) => ({ ...testCase, artifact: buildArtifact(testCase.entryPath, testCase.id) }));
const startedAt = performance.now();

try {
  await worker.start();
  for (const testCase of artifacts) {
    const load = await worker.request({
      type: "loadBot",
      botKey: testCase.id,
      source: testCase.artifact.source,
      fileName: testCase.artifact.fileName,
    });
    if (!load.ok) {
      throw new Error(`loadBot failed for ${testCase.id}: ${JSON.stringify(load)}`);
    }
  }

  const sessionReports = [];
  for (const testCase of artifacts) {
    sessionReports.push(await runTickSession(testCase));
  }

  const elapsedMs = performance.now() - startedAt;
  const report = buildReport({ elapsedMs, sessionReports });
  mkdirSync(dirname(config.out), { recursive: true });
  writeFileSync(config.out, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`arena runner tick smoke complete: ${resolve(config.out)}`);
  console.log(
    `bots=${sessionReports.length} ticks=${report.totals.ticks} actions=${report.totals.actions} venueCommands=${report.totals.venueCommands} failures=${report.totals.failures} p95=${report.latencyMs.p95.toFixed(2)}ms`,
  );
} finally {
  await worker.shutdown();
}

async function runTickSession(testCase) {
  const fixture = indexedFixture(baseFixture, testCase.id);
  const sessionId = `tick-${testCase.id}-${Date.now()}`;
  const start = await worker.request({
    type: "startSession",
    botKey: testCase.id,
    sessionId,
    fixture,
  });
  if (!start.ok) {
    throw new Error(`startSession failed for ${testCase.id}: ${JSON.stringify(start)}`);
  }
  const tickReports = [];
  for (const tick of fixture.ticks) {
    tickReports.push(await worker.request({ type: "runTick", sessionId, tick }));
  }
  const stop = await worker.request({ type: "stopSession", sessionId });
  return {
    botId: testCase.id,
    sessionId,
    start,
    ticks: tickReports,
    stop,
  };
}

function buildReport({ elapsedMs, sessionReports }) {
  const tickResponses = sessionReports.flatMap((session) => session.ticks);
  const latencies = tickResponses.map((tick) => Number(tick.elapsedMs ?? 0)).sort((a, b) => a - b);
  const totals = tickResponses.reduce(
    (acc, tick) => {
      acc.ticks += 1;
      acc.failures += tick.ok ? 0 : 1;
      acc.actions += tick.actions?.length ?? 0;
      acc.venueCommands += tick.venueCommands?.length ?? 0;
      acc.dataCalls += Number(tick.dataCalls ?? 0);
      acc.issues += tick.issues?.length ?? 0;
      return acc;
    },
    { ticks: 0, failures: 0, actions: 0, venueCommands: 0, dataCalls: 0, issues: 0 },
  );
  return {
    schemaVersion: "reef.arena.runnerTickSmoke.v0",
    generatedAt: new Date().toISOString(),
    config,
    elapsedMs,
    totals,
    latencyMs: {
      min: latencies[0] ?? 0,
      p50: percentile(latencies, 0.5),
      p95: percentile(latencies, 0.95),
      max: latencies[latencies.length - 1] ?? 0,
      avg: avg(latencies),
    },
    sessionReports,
  };
}

class RunnerWorker {
  constructor(workerId, compartment) {
    this.workerId = workerId;
    this.compartment = compartment;
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = "";
  }

  async start() {
    const workerScript = new URL("./arena-runner-pool-worker.mjs", import.meta.url).pathname;
    this.child = spawn(
      "bun",
      [workerScript, `--worker-id=${this.workerId}`, `--compartment=${this.compartment}`],
      { cwd: repoRoot, stdio: ["pipe", "pipe", "pipe"] },
    );
    this.child.stdout.setEncoding("utf8");
    this.child.stderr.setEncoding("utf8");
    this.child.stdout.on("data", (chunk) => this.handleStdout(chunk));
    this.child.stderr.on("data", (chunk) => process.stderr.write(`[${this.workerId}] ${chunk}`));
    this.child.on("close", (code) => {
      for (const pending of this.pending.values()) {
        pending.reject(new Error(`${this.workerId} exited with code ${code ?? "unknown"}`));
      }
      this.pending.clear();
    });
    await this.request({ type: "heartbeat" });
  }

  request(message, timeoutMs = 10000) {
    const id = `${this.workerId}-${this.nextId}`;
    this.nextId += 1;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${this.workerId} request ${message.type} timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(id, {
        resolve(response) {
          clearTimeout(timeout);
          resolve(response);
        },
        reject(error) {
          clearTimeout(timeout);
          reject(error);
        },
      });
      this.child.stdin.write(`${JSON.stringify({ id, ...message })}\n`);
    });
  }

  handleStdout(chunk) {
    this.buffer += chunk;
    let newlineIndex = this.buffer.indexOf("\n");
    while (newlineIndex >= 0) {
      const line = this.buffer.slice(0, newlineIndex);
      this.buffer = this.buffer.slice(newlineIndex + 1);
      if (line.trim().length > 0) {
        this.handleLine(line);
      }
      newlineIndex = this.buffer.indexOf("\n");
    }
  }

  handleLine(line) {
    const response = JSON.parse(line);
    const pending = this.pending.get(response.id);
    if (pending === undefined) {
      return;
    }
    this.pending.delete(response.id);
    pending.resolve(response);
  }

  async shutdown() {
    if (this.child === undefined || this.child.killed) return;
    try {
      await this.request({ type: "shutdown" }, 1000);
    } catch {
      this.child.kill("SIGKILL");
    }
  }
}

function buildArtifact(entryPath, name) {
  const artifactPath = join(outDir, `${name}.bundle.js`);
  const manifestPath = join(outDir, `${name}.bundle.manifest.json`);
  const build = spawnSync(
    "bun",
    [
      "scripts/dev/bot-sdk-build-hosted-artifact.mjs",
      entryPath,
      `--out=${artifactPath}`,
      `--manifest-out=${manifestPath}`,
    ],
    { cwd: repoRoot, encoding: "utf8", env: { ...process.env, BOT_SDK_BUILD_TMP_ROOT: tmpdir() } },
  );
  if (build.status !== 0) {
    throw new Error(`artifact build failed for ${entryPath}\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}`);
  }
  return {
    source: readFileSync(artifactPath, "utf8"),
    fileName: basename(artifactPath),
    manifest: JSON.parse(readFileSync(manifestPath, "utf8")),
  };
}

function benchCase(id) {
  switch (id) {
    case "simple":
      return { id, entryPath: "packages/bot-sdk/examples/simple-market-maker.ts" };
    case "lifecycle":
      return { id, entryPath: "packages/bot-sdk/examples/lifecycle-safe-market-maker.ts" };
    case "refreshing":
      return { id, entryPath: "packages/bot-sdk/examples/refreshing-market-maker.ts" };
    default:
      throw new Error(`unknown tick smoke bot ${id}; expected simple,lifecycle,refreshing`);
  }
}

function indexedFixture(fixture, botId) {
  return {
    ...fixture,
    runId: `${fixture.runId}-${botId}`,
    botId,
    actorId: `actor-${botId}`,
    correlationId: `${fixture.correlationId}-${botId}`,
  };
}

function percentile(sortedValues, pct) {
  if (sortedValues.length === 0) return 0;
  const index = Math.min(sortedValues.length - 1, Math.ceil(sortedValues.length * pct) - 1);
  return sortedValues[index];
}

function avg(values) {
  return values.length === 0 ? 0 : values.reduce((sum, value) => sum + value, 0) / values.length;
}

function csvOption(name, fallback) {
  return stringOption(name, fallback).split(",").map((value) => value.trim()).filter(Boolean);
}

function stringOption(name, fallback) {
  const raw = optionValue(name);
  return raw === undefined ? fallback : raw;
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}
