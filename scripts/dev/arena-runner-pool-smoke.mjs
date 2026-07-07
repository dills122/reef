import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);
const config = {
  workers: numberOption("--workers", 2),
  bots: csvOption("--bots", "simple,lifecycle,refreshing,multi-symbol"),
  iterations: numberOption("--iterations", 10),
  concurrency: numberOption("--concurrency", 4),
  compartment: stringOption("--compartment", "vm"),
  tickTimeoutMs: numberOption("--tick-timeout-ms", 1000),
  lifecycleTimeoutMs: numberOption("--lifecycle-timeout-ms", 1000),
  buildTmpRoot: stringOption("--build-tmp-root", "tmp"),
  out: stringOption("--out", "/tmp/reef-arena-runner-pool-smoke.json"),
};

if (!["vm", "ses"].includes(config.compartment)) {
  console.error(`unsupported --compartment=${config.compartment}; expected vm or ses`);
  process.exit(2);
}

const baseFixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const outDir = mkdtempSync(join(tmpdir(), "reef-arena-runner-pool-"));
const cases = config.bots.map((id) => benchCase(id));
const artifacts = cases.map((testCase) => ({ ...testCase, artifact: buildArtifact(testCase.entryPath, testCase.id) }));
const pool = new RunnerPool(config.workers, config.compartment);

const startedAt = performance.now();
try {
  await pool.start();
  const loadStartedAt = performance.now();
  await loadArtifactsIntoPool(pool, artifacts);
  const loadElapsedMs = performance.now() - loadStartedAt;
  const heartbeatBefore = await pool.heartbeatAll();
  const tasks = [];
  for (let iteration = 0; iteration < config.iterations; iteration += 1) {
    for (const testCase of artifacts) {
      tasks.push({ iteration, testCase });
    }
  }
  const runStartedAt = performance.now();
  const runReports = await runWithConcurrency(tasks, config.concurrency, (task) => runScenarioTask(pool, task));
  const runElapsedMs = performance.now() - runStartedAt;
  const freezeResult = await pool.broadcastFreeze(artifacts[0]?.id ?? "missing", "pool smoke lifecycle check");
  const heartbeatAfter = await pool.heartbeatAll();
  const elapsedMs = performance.now() - startedAt;
  const report = buildReport({
    loadElapsedMs,
    runElapsedMs,
    elapsedMs,
    heartbeatBefore,
    heartbeatAfter,
    freezeResult,
    runReports,
    artifacts,
  });
  mkdirSync(dirname(config.out), { recursive: true });
  writeFileSync(config.out, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`arena runner pool smoke complete: ${resolve(config.out)}`);
  console.log(
    `workers=${config.workers} bots=${artifacts.length} runs=${report.totals.runs} compartment=${config.compartment} loadMs=${loadElapsedMs.toFixed(2)} runMs=${runElapsedMs.toFixed(2)}`,
  );
  console.log(
    `runRps=${report.throughput.runsPerSecond.toFixed(2)} actionRps=${report.throughput.actionsPerSecond.toFixed(2)} p95=${report.latencyMs.p95.toFixed(2)}ms failed=${report.totals.failed}`,
  );
} finally {
  await pool.shutdown();
}

async function loadArtifactsIntoPool(poolValue, artifactCases) {
  for (const worker of poolValue.workers) {
    for (const testCase of artifactCases) {
      const response = await worker.request({
        type: "loadBot",
        botKey: testCase.id,
        source: testCase.artifact.source,
        fileName: testCase.artifact.fileName,
        executionLimits: {
          tickTimeoutMs: config.tickTimeoutMs,
          lifecycleTimeoutMs: config.lifecycleTimeoutMs,
        },
      });
      if (!response.ok) {
        throw new Error(`failed to load ${testCase.id} into ${worker.workerId}: ${JSON.stringify(response.issues ?? response.error)}`);
      }
    }
  }
}

async function runScenarioTask(poolValue, task) {
  const started = performance.now();
  const response = await poolValue.request({
    type: "runScenario",
    botKey: task.testCase.id,
    fixture: task.testCase.fixture(task.iteration),
  });
  const elapsedMs = performance.now() - started;
  return {
    caseId: task.testCase.id,
    iteration: task.iteration,
    workerId: response.workerId,
    ok: response.ok,
    status: response.status,
    elapsedMs,
    workerElapsedMs: Number(response.elapsedMs ?? 0),
    cpuUsageMicros: Number(response.cpuUsageMicros ?? 0),
    summary: response.summary,
    error: response.error,
    resourceReport: response.resourceReport,
  };
}

function buildReport({ loadElapsedMs, runElapsedMs, elapsedMs, heartbeatBefore, heartbeatAfter, freezeResult, runReports, artifacts }) {
  const latencies = runReports.map((result) => result.elapsedMs).sort((a, b) => a - b);
  const workerLatencies = runReports.map((result) => result.workerElapsedMs).sort((a, b) => a - b);
  const totals = runReports.reduce(
    (acc, result) => {
      acc.runs += 1;
      acc.completed += result.ok ? 1 : 0;
      acc.failed += result.ok ? 0 : 1;
      acc.ticks += Number(result.summary?.ticksRun ?? 0);
      acc.actions += Number(result.summary?.actionsProposed ?? 0);
      acc.orderActions += Number(result.summary?.orderActionsProposed ?? 0);
      acc.dataCalls += Number(result.summary?.dataCalls ?? 0);
      acc.signals += Number(result.summary?.signalsGenerated ?? 0);
      acc.events += Number(result.summary?.eventsProcessed ?? 0);
      acc.venueCommands += Number(result.summary?.venueCommands ?? 0);
      acc.cpuUsageMicros += Number(result.cpuUsageMicros ?? 0);
      return acc;
    },
    { runs: 0, completed: 0, failed: 0, ticks: 0, actions: 0, orderActions: 0, dataCalls: 0, signals: 0, events: 0, venueCommands: 0, cpuUsageMicros: 0 },
  );
  return {
    generatedAt: new Date().toISOString(),
    config,
    artifactDir: outDir,
    artifacts: artifacts.map((testCase) => ({
      id: testCase.id,
      entryPath: testCase.entryPath,
      fileName: testCase.artifact.fileName,
      manifest: testCase.artifact.manifest,
    })),
    loadElapsedMs,
    runElapsedMs,
    elapsedMs,
    totals,
    throughput: {
      runsPerSecond: rate(totals.runs, runElapsedMs),
      ticksPerSecond: rate(totals.ticks, runElapsedMs),
      actionsPerSecond: rate(totals.actions, runElapsedMs),
      orderActionsPerSecond: rate(totals.orderActions, runElapsedMs),
      dataCallsPerSecond: rate(totals.dataCalls, runElapsedMs),
      venueCommandsPerSecond: rate(totals.venueCommands, runElapsedMs),
    },
    latencyMs: {
      min: latencies[0] ?? 0,
      p50: percentile(latencies, 0.5),
      p95: percentile(latencies, 0.95),
      max: latencies[latencies.length - 1] ?? 0,
      avg: avg(latencies),
    },
    workerLatencyMs: {
      min: workerLatencies[0] ?? 0,
      p50: percentile(workerLatencies, 0.5),
      p95: percentile(workerLatencies, 0.95),
      max: workerLatencies[workerLatencies.length - 1] ?? 0,
      avg: avg(workerLatencies),
    },
    heartbeatBefore,
    heartbeatAfter,
    freezeResult,
    runReports,
  };
}

class RunnerPool {
  constructor(size, compartment) {
    this.nextWorker = 0;
    this.workers = Array.from({ length: size }, (_, index) => new RunnerWorker(`runner-worker-${index}`, compartment));
  }

  async start() {
    await Promise.all(this.workers.map((worker) => worker.start()));
  }

  request(message) {
    const worker = this.workers[this.nextWorker % this.workers.length];
    this.nextWorker += 1;
    return worker.request(message);
  }

  heartbeatAll() {
    return Promise.all(this.workers.map((worker) => worker.request({ type: "heartbeat" })));
  }

  broadcastFreeze(botKey, reason) {
    return Promise.all(this.workers.map((worker) => worker.request({ type: "freezeBot", botKey, reason })));
  }

  async shutdown() {
    await Promise.all(this.workers.map((worker) => worker.shutdown()));
  }
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
    const payload = { id, ...message };
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
      this.child.stdin.write(`${JSON.stringify(payload)}\n`);
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
    let response;
    try {
      response = JSON.parse(line);
    } catch (error) {
      throw new Error(`${this.workerId} emitted invalid JSON: ${line}`);
    }
    const pending = this.pending.get(response.id);
    if (pending === undefined) {
      return;
    }
    this.pending.delete(response.id);
    pending.resolve(response);
  }

  async shutdown() {
    if (this.child === undefined || this.child.killed) {
      return;
    }
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
    {
      cwd: repoRoot,
      encoding: "utf8",
      env: buildEnv(),
    },
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
      return { id, entryPath: "packages/bot-sdk/examples/simple-market-maker.ts", fixture: indexedFixture(baseFixture, id) };
    case "lifecycle":
      return { id, entryPath: "packages/bot-sdk/examples/lifecycle-safe-market-maker.ts", fixture: indexedFixture(baseFixture, id) };
    case "refreshing":
      return { id, entryPath: "packages/bot-sdk/examples/refreshing-market-maker.ts", fixture: indexedFixture(baseFixture, id) };
    case "multi-symbol":
      return { id, entryPath: "packages/bot-sdk/examples/multi-symbol-strategy-bot.ts", fixture: (iteration) => multiSymbolFixture("multi-symbol-strategy", iteration) };
    case "technical":
      return { id, entryPath: "packages/bot-sdk/examples/technical-indicator-strategy-bot.ts", fixture: (iteration) => multiSymbolFixture("technical-indicator-strategy", iteration) };
    default:
      throw new Error(`unknown pool smoke bot ${id}; expected simple,lifecycle,refreshing,multi-symbol,technical`);
  }
}

function indexedFixture(fixture, botId) {
  return (iteration) => ({
    ...fixture,
    runId: `${fixture.runId}-${botId}-${iteration}`,
    botId,
    actorId: `actor-${botId}`,
    correlationId: `${fixture.correlationId}-${botId}-${iteration}`,
  });
}

function multiSymbolFixture(botId, iteration) {
  return {
    ...indexedFixture(baseFixture, botId)(iteration),
    historicalBars: {
      AAPL: bars("AAPL", [
        100, 100, 100, 100, 100, 100, 100, 100, 100, 100,
        100, 100, 100, 100, 100, 80, 85, 90, 92, 95,
      ]),
      MSFT: bars("MSFT", [
        200, 200, 200, 200, 200, 200, 200, 200, 200, 200,
        200, 200, 200, 200, 200, 220, 215, 212, 210, 205,
      ]),
    },
    ticks: baseFixture.ticks.map((tick) => ({
      ...tick,
      marketSnapshots: {
        ...tick.marketSnapshots,
        MSFT: { instrumentId: "MSFT", asOf: tick.occurredAt, bidPrice: 211, askPrice: 213, midPrice: 212, lastPrice: 212 },
        NVDA: { instrumentId: "NVDA", asOf: tick.occurredAt, bidPrice: 499, askPrice: 501, midPrice: 500, lastPrice: 500 },
        TSLA: { instrumentId: "TSLA", asOf: tick.occurredAt, bidPrice: 249, askPrice: 251, midPrice: 250, lastPrice: 250 },
      },
    })),
  };
}

function bars(instrumentId, closes) {
  return closes.map((close, index) => ({
    instrumentId,
    start: `2026-07-04T14:${String(index).padStart(2, "0")}:00.000Z`,
    end: `2026-07-04T14:${String(index + 1).padStart(2, "0")}:00.000Z`,
    open: close,
    high: close + 1,
    low: close - 1,
    close,
    volume: 1000 + index,
  }));
}

async function runWithConcurrency(tasks, concurrency, fn) {
  const results = new Array(tasks.length);
  let next = 0;
  const workers = Array.from({ length: Math.max(1, concurrency) }, async () => {
    while (next < tasks.length) {
      const index = next;
      next += 1;
      results[index] = await fn(tasks[index]);
    }
  });
  await Promise.all(workers);
  return results;
}

function buildEnv() {
  if (config.buildTmpRoot === "repo") {
    return process.env;
  }
  const root = config.buildTmpRoot === "tmp" ? tmpdir() : config.buildTmpRoot;
  return { ...process.env, BOT_SDK_BUILD_TMP_ROOT: root };
}

function percentile(sortedValues, pct) {
  if (sortedValues.length === 0) return 0;
  const index = Math.min(sortedValues.length - 1, Math.ceil(sortedValues.length * pct) - 1);
  return sortedValues[index];
}

function avg(values) {
  return values.length === 0 ? 0 : values.reduce((sum, value) => sum + value, 0) / values.length;
}

function rate(count, elapsedMs) {
  return elapsedMs > 0 ? count / (elapsedMs / 1000) : 0;
}

function csvOption(name, fallback) {
  return stringOption(name, fallback).split(",").map((value) => value.trim()).filter(Boolean);
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

function stringOption(name, fallback) {
  return optionValue(name) ?? fallback;
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}
