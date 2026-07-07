import { isMainThread, parentPort, workerData, Worker } from "node:worker_threads";
import { stdin } from "node:process";
import { performance } from "node:perf_hooks";

if (isMainThread) {
  await runRunner();
} else {
  await runWorker(workerData);
}

async function runRunner() {
  const config = JSON.parse(await readStdin());
  const startedAt = performance.now();
  const beforeCpu = process.cpuUsage();
  const reports = await Promise.all(
    Array.from({ length: config.workers }, (_, workerId) => runBenchWorker({ ...config, workerId })),
  );
  const elapsedMs = performance.now() - startedAt;
  const cpuUsage = process.cpuUsage(beforeCpu);
  const totals = reports.reduce(
    (acc, report) => {
      acc.bots += report.bots;
      acc.ticks += report.ticks;
      acc.actions += report.actions;
      acc.lateTicks += report.lateTicks;
      acc.outputBytes += report.outputBytes;
      acc.maxQueueDepth = Math.max(acc.maxQueueDepth, report.queue.maxDepth);
      return acc;
    },
    { bots: 0, ticks: 0, actions: 0, lateTicks: 0, outputBytes: 0, maxQueueDepth: 0 },
  );
  const report = {
    runnerId: config.runnerId,
    runtime: "node-worker",
    useSes: config.useSes,
    workers: config.workers,
    botsPerWorker: config.botsPerWorker,
    elapsedMs,
    setupMs: max(reports.map((workerReport) => workerReport.setupMs)),
    cpuUsageMicros: cpuUsage.user + cpuUsage.system,
    totals,
    throughput: {
      ticksPerSecond: rate(totals.ticks, elapsedMs),
      actionsPerSecond: rate(totals.actions, elapsedMs),
    },
    tickLatency: mergeTickStats(reports.map((workerReport) => workerReport.tickLatency)),
    queue: {
      maxDepth: totals.maxQueueDepth,
      staleTicksDropped: 0,
    },
    memoryUsage: process.memoryUsage(),
    workerReports: reports,
  };
  console.log(JSON.stringify(report));
}

function runBenchWorker(config) {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL(import.meta.url), { workerData: config });
    worker.on("message", (report) => {
      worker.terminate();
      resolve(report);
    });
    worker.on("error", (error) => {
      worker.terminate();
      reject(error);
    });
  });
}

async function runWorker(config) {
  const beforeCpu = process.cpuUsage();
  const setupStartedAt = performance.now();
  const bots = await createBots(config);
  const setupMs = performance.now() - setupStartedAt;
  const startedAt = performance.now();
  const tickLatency = emptyTickStats();
  let actions = 0;
  let outputBytes = 0;

  for (let tick = 0; tick < config.ticksPerBot; tick += 1) {
    const snapshot = createSnapshot(tick);
    for (const bot of bots) {
      const tickStartedAt = performance.now();
      const proposed = bot.onTick(snapshot);
      const elapsedMs = performance.now() - tickStartedAt;
      recordTick(tickLatency, elapsedMs, config.tickDeadlineMs);
      actions += proposed.length;
      outputBytes += JSON.stringify(proposed).length;
    }
  }

  const elapsedMs = performance.now() - startedAt;
  const cpuUsage = process.cpuUsage(beforeCpu);
  parentPort.postMessage({
    runnerId: config.runnerId,
    workerId: config.workerId,
    bots: bots.length,
    ticks: bots.length * config.ticksPerBot,
    actions,
    lateTicks: tickLatency.late,
    outputBytes,
    elapsedMs,
    setupMs,
    cpuUsageMicros: cpuUsage.user + cpuUsage.system,
    tickLatency,
    queue: {
      maxDepth: 0,
      staleTicksDropped: 0,
    },
    memoryUsage: process.memoryUsage(),
  });
}

async function createBots(config) {
  if (config.useSes) {
    await import("ses");
    lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });
  }

  const bots = [];
  for (let botIndex = 0; botIndex < config.botsPerWorker; botIndex += 1) {
    bots.push(config.useSes ? createSesBot(config, botIndex) : createNativeBot(config, botIndex));
  }
  return bots;
}

function createNativeBot(config, botIndex) {
  let inventory = 0;
  return {
    onTick(snapshot) {
      return decideActions(snapshot, config, botIndex, () => {
        inventory += Math.sin(snapshot.sequence + botIndex) > 0 ? 1 : -1;
        return inventory;
      });
    },
  };
}

function createSesBot(config, botIndex) {
  const compartment = new Compartment({
    Math,
    config: Object.freeze({ actionsPerTick: config.actionsPerTick, workUnits: config.workUnits, botIndex }),
  });
  return compartment.evaluate(`
    (() => {
      let inventory = 0;
      function burn(units, seed) {
        let value = seed + 1;
        for (let i = 0; i < units; i += 1) {
          value = (value * 1664525 + 1013904223) % 4294967296;
        }
        return value;
      }
      return {
        onTick(snapshot) {
          const value = burn(config.workUnits, snapshot.sequence + config.botIndex);
          inventory += Math.sin(snapshot.midPrice + value) > 0 ? 1 : -1;
          const actions = [];
          for (let i = 0; i < config.actionsPerTick; i += 1) {
            actions.push({
              type: "submit_limit",
              side: i % 2 === 0 ? "BUY" : "SELL",
              quantity: 1,
              price: snapshot.midPrice + (i % 2 === 0 ? -1 : 1) * (1 + Math.abs(inventory % 5)) * snapshot.tickSize,
            });
          }
          return actions;
        },
      };
    })()
  `);
}

function createSnapshot(sequence) {
  return {
    sequence,
    midPrice: 100 + Math.sin(sequence / 10),
    tickSize: 0.01,
  };
}

function decideActions(snapshot, config, botIndex, updateInventory) {
  const value = burn(config.workUnits, snapshot.sequence + botIndex);
  const inventory = updateInventory() + (value % 3) - 1;
  const actions = [];
  for (let i = 0; i < config.actionsPerTick; i += 1) {
    actions.push({
      type: "submit_limit",
      side: i % 2 === 0 ? "BUY" : "SELL",
      quantity: 1,
      price: snapshot.midPrice + (i % 2 === 0 ? -1 : 1) * (1 + Math.abs(inventory % 5)) * snapshot.tickSize,
    });
  }
  return actions;
}

function burn(units, seed) {
  let value = seed + 1;
  for (let i = 0; i < units; i += 1) {
    value = (value * 1664525 + 1013904223) % 4294967296;
  }
  return value;
}

function emptyTickStats() {
  return { count: 0, minMs: Number.POSITIVE_INFINITY, maxMs: 0, totalMs: 0, late: 0 };
}

function recordTick(stats, elapsedMs, deadlineMs) {
  stats.count += 1;
  stats.minMs = Math.min(stats.minMs, elapsedMs);
  stats.maxMs = Math.max(stats.maxMs, elapsedMs);
  stats.totalMs += elapsedMs;
  if (elapsedMs > deadlineMs) {
    stats.late += 1;
  }
}

function mergeTickStats(allStats) {
  const merged = allStats.reduce((acc, stats) => {
    acc.count += stats.count;
    acc.minMs = Math.min(acc.minMs, stats.minMs);
    acc.maxMs = Math.max(acc.maxMs, stats.maxMs);
    acc.totalMs += stats.totalMs;
    acc.late += stats.late;
    return acc;
  }, emptyTickStats());
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

async function readStdin() {
  let body = "";
  stdin.setEncoding("utf8");
  for await (const chunk of stdin) {
    body += chunk;
  }
  return body;
}
