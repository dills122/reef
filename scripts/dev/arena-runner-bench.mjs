import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { performance } from "node:perf_hooks";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);

const config = {
  runtime: stringOption("--runtime", "node-worker"),
  runnerProcesses: numberOption("--runner-processes", 1),
  workersPerRunner: numberOption("--workers-per-runner", 2),
  botsPerWorker: numberOption("--bots-per-worker", 4),
  ticksPerBot: numberOption("--ticks-per-bot", 100),
  actionsPerTick: numberOption("--actions-per-tick", 5),
  workUnits: numberOption("--work-units", 1000),
  tickDeadlineMs: numberOption("--tick-deadline-ms", 50),
  useSes: booleanOption("--ses", false),
  rssSampleMs: numberOption("--rss-sample-ms", 50),
  out: stringOption("--out", "/tmp/reef-arena-runner-bench.json"),
};

if (!["deno-worker", "node-worker"].includes(config.runtime)) {
  console.error(`unsupported --runtime=${config.runtime}; expected deno-worker or node-worker`);
  process.exit(2);
}

const runtimeExecutable = resolveRuntimeExecutable(config.runtime);
const runtimeVersion = runtimeExecutable.versionCommand === undefined
  ? ""
  : spawnSync(runtimeExecutable.path, runtimeExecutable.versionCommand, { encoding: "utf8" }).stdout.trim();
const startedAt = performance.now();
const runnerReports = await Promise.all(
  Array.from({ length: config.runnerProcesses }, (_, runnerId) => runRunnerProcess(runtimeExecutable, runnerId)),
);
const elapsedMs = performance.now() - startedAt;
const report = buildReport({ runtimeVersion, elapsedMs, runnerReports });

mkdirSync(dirname(config.out), { recursive: true });
writeFileSync(config.out, JSON.stringify(report, null, 2));

console.log(`arena runner bench complete: ${resolve(config.out)}`);
console.log(
  `runners=${config.runnerProcesses} workers=${config.runnerProcesses * config.workersPerRunner} bots=${report.totals.bots} ticks=${report.totals.ticks} actions=${report.totals.actions}`,
);
console.log(
  `elapsed=${elapsedMs.toFixed(2)}ms setup=${report.setupMs.toFixed(2)}ms tickRps=${report.throughput.ticksPerSecond.toFixed(2)} actionRps=${report.throughput.actionsPerSecond.toFixed(2)} lateTicks=${report.totals.lateTicks}`,
);
console.log(
  `rssPeakMiB=${(report.memory.rssPeakBytes / 1024 / 1024).toFixed(2)} outputMiB=${(report.totals.outputBytes / 1024 / 1024).toFixed(2)} ses=${config.useSes} runtime=${config.runtime}`,
);

async function runRunnerProcess(runtimeExecutable, runnerId) {
  const payload = {
    runnerId,
    workers: config.workersPerRunner,
    botsPerWorker: config.botsPerWorker,
    ticksPerBot: config.ticksPerBot,
    actionsPerTick: config.actionsPerTick,
    workUnits: config.workUnits,
    tickDeadlineMs: config.tickDeadlineMs,
    useSes: config.useSes,
  };
  return new Promise((resolveReport, reject) => {
    const processStartedAt = performance.now();
    const child = spawn(
      runtimeExecutable.path,
      runtimeExecutable.args,
      {
        cwd: repoRoot,
        stdio: ["pipe", "pipe", "pipe"],
      },
    );
    let stdout = "";
    let stderr = "";
    const rssSamples = [];
    const rssTimer = setInterval(() => {
      const rssBytes = sampleRssBytes(child.pid);
      if (rssBytes !== undefined) {
        rssSamples.push({ atMs: performance.now() - startedAt, rssBytes });
      }
    }, config.rssSampleMs);

    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("error", (error) => {
      clearInterval(rssTimer);
      reject(error);
    });
    child.on("close", (code) => {
      clearInterval(rssTimer);
      if (code !== 0) {
        reject(new Error(`${config.runtime} runner ${runnerId} exited with code ${code}: ${stderr || stdout}`));
        return;
      }
      const parsed = parseLastJsonLine(stdout);
      if (parsed === undefined) {
        reject(new Error(`${config.runtime} runner ${runnerId} did not emit JSON: ${stderr || stdout}`));
        return;
      }
      resolveReport({
        ...parsed,
        process: {
          pid: child.pid,
          elapsedMs: performance.now() - processStartedAt,
          rssPeakBytes: Math.max(
            Number(parsed.memoryUsage?.rss ?? 0),
            ...rssSamples.map((sample) => sample.rssBytes),
          ),
          rssSamples,
        },
      });
    });
    child.stdin.end(JSON.stringify(payload));
  });
}

function buildReport({ runtimeVersion, elapsedMs, runnerReports }) {
  const totals = runnerReports.reduce(
    (acc, runner) => {
      acc.bots += runner.totals.bots;
      acc.ticks += runner.totals.ticks;
      acc.actions += runner.totals.actions;
      acc.lateTicks += runner.totals.lateTicks;
      acc.outputBytes += Number(runner.totals.outputBytes ?? 0);
      acc.maxQueueDepth = Math.max(acc.maxQueueDepth, Number(runner.queue?.maxDepth ?? runner.totals.maxQueueDepth ?? 0));
      return acc;
    },
    { bots: 0, ticks: 0, actions: 0, lateTicks: 0, outputBytes: 0, maxQueueDepth: 0 },
  );
  return {
    generatedAt: new Date().toISOString(),
    config,
    runtime: {
      driver: "bun-or-node",
      runner: config.runtime,
      version: runtimeVersion,
    },
    elapsedMs,
    setupMs: max(runnerReports.map((runner) => Number(runner.setupMs ?? 0))),
    runnerProcessElapsedMs: max(runnerReports.map((runner) => Number(runner.process?.elapsedMs ?? 0))),
    totals,
    throughput: {
      ticksPerSecond: rate(totals.ticks, elapsedMs),
      actionsPerSecond: rate(totals.actions, elapsedMs),
    },
    tickLatency: mergeTickStats(runnerReports.map((runner) => runner.tickLatency)),
    queue: {
      maxDepth: totals.maxQueueDepth,
      staleTicksDropped: runnerReports.reduce((total, runner) => total + Number(runner.queue?.staleTicksDropped ?? 0), 0),
    },
    memory: {
      rssPeakBytes: runnerReports.reduce((total, runner) => total + runner.process.rssPeakBytes, 0),
      largestRunnerRssPeakBytes: Math.max(0, ...runnerReports.map((runner) => runner.process.rssPeakBytes)),
      runnerRssPeakBytes: runnerReports.map((runner) => ({
        runnerId: runner.runnerId,
        rssPeakBytes: runner.process.rssPeakBytes,
      })),
    },
    runnerReports,
  };
}

function resolveRuntimeExecutable(runtime) {
  if (runtime === "deno-worker") {
    const denoPath = findExecutable("deno");
    if (denoPath === undefined) {
      console.error("Deno is required for --runtime=deno-worker but was not found on PATH.");
      console.error("Install Deno or run this script in the planned Deno runner container.");
      process.exit(2);
    }
    const runnerScript = new URL("./arena-runner-bench-deno.ts", import.meta.url).pathname;
    return {
      path: denoPath,
      args: ["run", "--no-prompt", "--no-config", "--no-lock", runnerScript],
      versionCommand: ["--version"],
    };
  }

  const nodePath = findExecutable("node");
  if (nodePath === undefined) {
    console.error("Node is required for --runtime=node-worker but was not found on PATH.");
    process.exit(2);
  }
  const runnerScript = new URL("./arena-runner-bench-node.mjs", import.meta.url).pathname;
  return {
    path: nodePath,
    args: [runnerScript],
    versionCommand: ["--version"],
  };
}

function sampleRssBytes(pid) {
  if (pid === undefined) return undefined;
  const result = spawnSync("ps", ["-o", "rss=", "-p", String(pid)], { encoding: "utf8" });
  if (result.status !== 0) return undefined;
  const rssKiB = Number(result.stdout.trim());
  return Number.isFinite(rssKiB) ? rssKiB * 1024 : undefined;
}

function mergeTickStats(allStats) {
  const merged = allStats.reduce(
    (acc, stats) => {
      acc.count += Number(stats?.count ?? 0);
      acc.minMs = Math.min(acc.minMs, Number(stats?.minMs ?? Number.POSITIVE_INFINITY));
      acc.maxMs = Math.max(acc.maxMs, Number(stats?.maxMs ?? 0));
      acc.totalMs += Number(stats?.totalMs ?? 0);
      acc.late += Number(stats?.late ?? 0);
      return acc;
    },
    { count: 0, minMs: Number.POSITIVE_INFINITY, maxMs: 0, totalMs: 0, late: 0 },
  );
  return {
    ...merged,
    minMs: merged.count === 0 ? 0 : merged.minMs,
    avgMs: merged.count === 0 ? 0 : merged.totalMs / merged.count,
  };
}

function rate(count, elapsedMs) {
  return elapsedMs > 0 ? count / (elapsedMs / 1000) : 0;
}

function max(values) {
  return values.reduce((largest, value) => Math.max(largest, Number(value) || 0), 0);
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

function findExecutable(name) {
  const result = spawnSync("command", ["-v", name], { shell: true, encoding: "utf8" });
  if (result.status !== 0) return undefined;
  return result.stdout.trim() || undefined;
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

function booleanOption(name, fallback) {
  const raw = optionValue(name);
  if (raw === undefined) return fallback;
  if (raw === "1" || raw === "true") return true;
  if (raw === "0" || raw === "false") return false;
  throw new Error(`${name} must be true/false or 1/0; got ${raw}`);
}

function stringOption(name, fallback) {
  return optionValue(name) ?? fallback;
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}
