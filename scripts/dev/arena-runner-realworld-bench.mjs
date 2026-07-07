import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import vm from "node:vm";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);

const config = {
  bots: csvOption("--bots", "simple,lifecycle,refreshing,multi-symbol"),
  iterations: numberOption("--iterations", 25),
  concurrency: numberOption("--concurrency", 4),
  compartment: stringOption("--compartment", "vm"),
  tickTimeoutMs: numberOption("--tick-timeout-ms", 1000),
  lifecycleTimeoutMs: numberOption("--lifecycle-timeout-ms", 1000),
  buildTmpRoot: stringOption("--build-tmp-root", "tmp"),
  out: stringOption("--out", "/tmp/reef-arena-runner-realworld-bench.json"),
};

if (!["vm", "ses"].includes(config.compartment)) {
  console.error(`unsupported --compartment=${config.compartment}; expected vm or ses`);
  process.exit(2);
}

const hostedRunner = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/hosted-runner.ts")).href);
const baseFixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const outDir = mkdtempSync(join(tmpdir(), "reef-arena-runner-realworld-"));
const selectedCases = config.bots.map((id) => benchCase(id));

if (config.compartment === "ses") {
  await import("ses");
  lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });
}

const buildStartedAt = performance.now();
const builtCases = selectedCases.map((testCase) => ({
  ...testCase,
  artifact: buildArtifact(testCase.entryPath, testCase.id),
}));
const buildElapsedMs = performance.now() - buildStartedAt;
const tasks = [];
for (let iteration = 0; iteration < config.iterations; iteration += 1) {
  for (const testCase of builtCases) {
    tasks.push({ iteration, testCase });
  }
}

const startedAt = performance.now();
const beforeCpu = process.cpuUsage();
const reports = await runWithConcurrency(tasks, config.concurrency, runBenchTask);
const elapsedMs = performance.now() - startedAt;
const cpuUsage = process.cpuUsage(beforeCpu);
const report = buildReport({ buildElapsedMs, elapsedMs, cpuUsage, reports, builtCases });

mkdirSync(dirname(config.out), { recursive: true });
writeFileSync(config.out, `${JSON.stringify(report, null, 2)}\n`);

console.log(`arena runner real-world bench complete: ${resolve(config.out)}`);
console.log(
  `cases=${builtCases.length} iterations=${config.iterations} runs=${report.totals.runs} concurrency=${config.concurrency} compartment=${config.compartment}`,
);
console.log(
  `elapsed=${elapsedMs.toFixed(2)}ms runRps=${report.throughput.runsPerSecond.toFixed(2)} actionRps=${report.throughput.actionsPerSecond.toFixed(2)} dataCallRps=${report.throughput.dataCallsPerSecond.toFixed(2)}`,
);
console.log(
  `p50=${report.latencyMs.p50.toFixed(2)}ms p95=${report.latencyMs.p95.toFixed(2)}ms max=${report.latencyMs.max.toFixed(2)}ms rssMiB=${(report.memory.rss / 1024 / 1024).toFixed(2)}`,
);

async function runBenchTask(task) {
  const runStartedAt = performance.now();
  const report = await hostedRunner.runHostedBotScenarioV1({
    source: task.testCase.artifact.source,
    fileName: task.testCase.artifact.fileName,
    fixture: task.testCase.fixture(),
    ...(config.compartment === "vm" ? { compartmentFactory: createVmCompartmentFactory() } : {}),
    executionLimits: {
      tickTimeoutMs: config.tickTimeoutMs,
      lifecycleTimeoutMs: config.lifecycleTimeoutMs,
    },
  });
  const elapsedMs = performance.now() - runStartedAt;
  return {
    caseId: task.testCase.id,
    iteration: task.iteration,
    elapsedMs,
    status: report.status,
    ticksRun: report.ticksRun,
    actionsProposed: report.actionsProposed,
    orderActionsProposed: report.orderActionsProposed,
    dataCalls: report.dataCalls,
    signalsGenerated: Number(report.signalsGenerated ?? 0),
    eventsProcessed: Number(report.eventsProcessed ?? 0),
    venueCommands: report.ticks.reduce((total, tick) => total + tick.venueCommands.length, 0),
    issues: report.issues,
    denials: report.denials,
    outputBytes: JSON.stringify(report).length,
  };
}

function buildReport({ buildElapsedMs, elapsedMs, cpuUsage, reports, builtCases }) {
  const latencies = reports.map((result) => result.elapsedMs).sort((a, b) => a - b);
  const byCase = {};
  for (const testCase of builtCases) {
    const caseReports = reports.filter((result) => result.caseId === testCase.id);
    byCase[testCase.id] = summarizeReports(caseReports);
  }
  const totals = reports.reduce(
    (acc, result) => {
      acc.runs += 1;
      acc.completed += result.status === "completed" ? 1 : 0;
      acc.failed += result.status === "completed" ? 0 : 1;
      acc.ticks += result.ticksRun;
      acc.actions += result.actionsProposed;
      acc.orderActions += result.orderActionsProposed;
      acc.dataCalls += result.dataCalls;
      acc.signals += result.signalsGenerated;
      acc.events += result.eventsProcessed;
      acc.venueCommands += result.venueCommands;
      acc.outputBytes += result.outputBytes;
      return acc;
    },
    { runs: 0, completed: 0, failed: 0, ticks: 0, actions: 0, orderActions: 0, dataCalls: 0, signals: 0, events: 0, venueCommands: 0, outputBytes: 0 },
  );
  return {
    generatedAt: new Date().toISOString(),
    config,
    artifactDir: outDir,
    artifacts: builtCases.map((testCase) => ({
      id: testCase.id,
      entryPath: testCase.entryPath,
      fileName: testCase.artifact.fileName,
      manifest: testCase.artifact.manifest,
    })),
    buildElapsedMs,
    elapsedMs,
    cpuUsageMicros: cpuUsage.user + cpuUsage.system,
    memory: process.memoryUsage(),
    totals,
    throughput: {
      runsPerSecond: rate(totals.runs, elapsedMs),
      ticksPerSecond: rate(totals.ticks, elapsedMs),
      actionsPerSecond: rate(totals.actions, elapsedMs),
      orderActionsPerSecond: rate(totals.orderActions, elapsedMs),
      dataCallsPerSecond: rate(totals.dataCalls, elapsedMs),
      venueCommandsPerSecond: rate(totals.venueCommands, elapsedMs),
    },
    latencyMs: {
      min: latencies[0] ?? 0,
      p50: percentile(latencies, 0.5),
      p95: percentile(latencies, 0.95),
      max: latencies[latencies.length - 1] ?? 0,
      avg: avg(latencies),
    },
    byCase,
    runReports: reports,
  };
}

function summarizeReports(reports) {
  const latencies = reports.map((result) => result.elapsedMs).sort((a, b) => a - b);
  const totals = reports.reduce(
    (acc, result) => {
      acc.runs += 1;
      acc.completed += result.status === "completed" ? 1 : 0;
      acc.failed += result.status === "completed" ? 0 : 1;
      acc.ticks += result.ticksRun;
      acc.actions += result.actionsProposed;
      acc.orderActions += result.orderActionsProposed;
      acc.dataCalls += result.dataCalls;
      acc.venueCommands += result.venueCommands;
      return acc;
    },
    { runs: 0, completed: 0, failed: 0, ticks: 0, actions: 0, orderActions: 0, dataCalls: 0, venueCommands: 0 },
  );
  return {
    totals,
    latencyMs: {
      min: latencies[0] ?? 0,
      p50: percentile(latencies, 0.5),
      p95: percentile(latencies, 0.95),
      max: latencies[latencies.length - 1] ?? 0,
      avg: avg(latencies),
    },
  };
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

function buildEnv() {
  if (config.buildTmpRoot === "repo") {
    return process.env;
  }
  const root = config.buildTmpRoot === "tmp" ? tmpdir() : config.buildTmpRoot;
  return { ...process.env, BOT_SDK_BUILD_TMP_ROOT: root };
}

function benchCase(id) {
  switch (id) {
    case "simple":
      return {
        id,
        entryPath: "packages/bot-sdk/examples/simple-market-maker.ts",
        fixture: () => baseFixture,
      };
    case "multi-symbol":
      return {
        id,
        entryPath: "packages/bot-sdk/examples/multi-symbol-strategy-bot.ts",
        fixture: () => multiSymbolFixture("multi-symbol-strategy"),
      };
    case "lifecycle":
      return {
        id,
        entryPath: "packages/bot-sdk/examples/lifecycle-safe-market-maker.ts",
        fixture: () => baseFixture,
      };
    case "refreshing":
      return {
        id,
        entryPath: "packages/bot-sdk/examples/refreshing-market-maker.ts",
        fixture: () => baseFixture,
      };
    case "technical":
      return {
        id,
        entryPath: "packages/bot-sdk/examples/technical-indicator-strategy-bot.ts",
        fixture: () => multiSymbolFixture("technical-indicator-strategy"),
      };
    default:
      throw new Error(`unknown bench bot ${id}; expected simple,lifecycle,refreshing,multi-symbol,technical`);
  }
}

function multiSymbolFixture(botId) {
  return {
    ...baseFixture,
    botId,
    actorId: `actor-${botId}`,
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
        MSFT: {
          instrumentId: "MSFT",
          asOf: tick.occurredAt,
          bidPrice: 211,
          askPrice: 213,
          midPrice: 212,
          lastPrice: 212,
        },
        NVDA: {
          instrumentId: "NVDA",
          asOf: tick.occurredAt,
          bidPrice: 499,
          askPrice: 501,
          midPrice: 500,
          lastPrice: 500,
        },
        TSLA: {
          instrumentId: "TSLA",
          asOf: tick.occurredAt,
          bidPrice: 249,
          askPrice: 251,
          midPrice: 250,
          lastPrice: 250,
        },
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

function createVmCompartmentFactory() {
  return {
    create(options) {
      return {
        evaluate(source) {
          const context = vm.createContext(Object.freeze({ ...options.endowments }));
          return new vm.Script(source, { filename: options.name }).runInContext(context, { timeout: 1000 });
        },
      };
    },
  };
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
