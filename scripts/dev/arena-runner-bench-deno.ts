type RunnerConfig = {
  runnerId: number;
  workers: number;
  botsPerWorker: number;
  ticksPerBot: number;
  actionsPerTick: number;
  workUnits: number;
  tickDeadlineMs: number;
  useSes: boolean;
};

type WorkerConfig = RunnerConfig & {
  workerId: number;
};

type TickStats = {
  count: number;
  minMs: number;
  maxMs: number;
  totalMs: number;
  late: number;
};

type WorkerReport = {
  runnerId: number;
  workerId: number;
  bots: number;
  ticks: number;
  actions: number;
  lateTicks: number;
  elapsedMs: number;
  tickLatency: TickStats;
  memoryUsage: Record<string, number>;
};

const url = new URL(import.meta.url);
const isWorker = url.searchParams.get("worker") === "1";

if (isWorker) {
  runWorker();
} else {
  await runRunner();
}

async function runRunner() {
  const config = JSON.parse(await readStdin()) as RunnerConfig;
  const startedAt = performance.now();
  const workerUrl = new URL(import.meta.url);
  workerUrl.searchParams.set("worker", "1");

  const reports = await Promise.all(
    Array.from({ length: config.workers }, (_, workerId) => runBenchWorker(workerUrl, { ...config, workerId })),
  );
  const elapsedMs = performance.now() - startedAt;
  const totals = reports.reduce(
    (acc, report) => {
      acc.bots += report.bots;
      acc.ticks += report.ticks;
      acc.actions += report.actions;
      acc.lateTicks += report.lateTicks;
      return acc;
    },
    { bots: 0, ticks: 0, actions: 0, lateTicks: 0 },
  );
  const tickLatency = mergeTickStats(reports.map((report) => report.tickLatency));
  const report = {
    runnerId: config.runnerId,
    runtime: "deno-worker",
    useSes: config.useSes,
    workers: config.workers,
    botsPerWorker: config.botsPerWorker,
    elapsedMs,
    totals,
    throughput: {
      ticksPerSecond: rate(totals.ticks, elapsedMs),
      actionsPerSecond: rate(totals.actions, elapsedMs),
    },
    tickLatency,
    memoryUsage: Deno.memoryUsage(),
    workerReports: reports,
  };
  console.log(JSON.stringify(report));
}

function runBenchWorker(workerUrl: URL, config: WorkerConfig): Promise<WorkerReport> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(workerUrl.href, { type: "module" });
    worker.onmessage = (event) => {
      worker.terminate();
      resolve(event.data as WorkerReport);
    };
    worker.onerror = (event) => {
      worker.terminate();
      reject(new Error(event.message));
    };
    worker.postMessage(config);
  });
}

function runWorker() {
  self.onmessage = async (event: MessageEvent<WorkerConfig>) => {
    const config = event.data;
    const bots = await createBots(config);
    const startedAt = performance.now();
    const tickLatency = emptyTickStats();
    let actions = 0;

    for (let tick = 0; tick < config.ticksPerBot; tick += 1) {
      const snapshot = createSnapshot(tick);
      for (const bot of bots) {
        const tickStartedAt = performance.now();
        const proposed = bot.onTick(snapshot);
        const elapsedMs = performance.now() - tickStartedAt;
        recordTick(tickLatency, elapsedMs, config.tickDeadlineMs);
        actions += proposed.length;
      }
    }

    const elapsedMs = performance.now() - startedAt;
    const report: WorkerReport = {
      runnerId: config.runnerId,
      workerId: config.workerId,
      bots: bots.length,
      ticks: bots.length * config.ticksPerBot,
      actions,
      lateTicks: tickLatency.late,
      elapsedMs,
      tickLatency,
      memoryUsage: Deno.memoryUsage(),
    };
    self.postMessage(report);
  };
}

async function createBots(config: WorkerConfig): Promise<Array<{ onTick: (snapshot: MarketSnapshot) => BotAction[] }>> {
  if (config.useSes) {
    await import("npm:ses@2.2.0");
    lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });
  }

  const bots = [];
  for (let botIndex = 0; botIndex < config.botsPerWorker; botIndex += 1) {
    bots.push(config.useSes ? createSesBot(config, botIndex) : createNativeBot(config, botIndex));
  }
  return bots;
}

function createNativeBot(config: WorkerConfig, botIndex: number) {
  let inventory = 0;
  return {
    onTick(snapshot: MarketSnapshot) {
      return decideActions(snapshot, config, botIndex, () => {
        inventory += Math.sin(snapshot.sequence + botIndex) > 0 ? 1 : -1;
        return inventory;
      });
    },
  };
}

function createSesBot(config: WorkerConfig, botIndex: number) {
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
  `) as { onTick: (snapshot: MarketSnapshot) => BotAction[] };
}

type MarketSnapshot = {
  sequence: number;
  midPrice: number;
  tickSize: number;
};

type BotAction = {
  type: "submit_limit";
  side: "BUY" | "SELL";
  quantity: number;
  price: number;
};

function createSnapshot(sequence: number): MarketSnapshot {
  return {
    sequence,
    midPrice: 100 + Math.sin(sequence / 10),
    tickSize: 0.01,
  };
}

function decideActions(
  snapshot: MarketSnapshot,
  config: WorkerConfig,
  botIndex: number,
  updateInventory: () => number,
): BotAction[] {
  const value = burn(config.workUnits, snapshot.sequence + botIndex);
  const inventory = updateInventory() + (value % 3) - 1;
  const actions = [];
  for (let i = 0; i < config.actionsPerTick; i += 1) {
    actions.push({
      type: "submit_limit",
      side: i % 2 === 0 ? "BUY" : "SELL",
      quantity: 1,
      price: snapshot.midPrice + (i % 2 === 0 ? -1 : 1) * (1 + Math.abs(inventory % 5)) * snapshot.tickSize,
    } as BotAction);
  }
  return actions;
}

function burn(units: number, seed: number): number {
  let value = seed + 1;
  for (let i = 0; i < units; i += 1) {
    value = (value * 1664525 + 1013904223) % 4294967296;
  }
  return value;
}

function emptyTickStats(): TickStats {
  return { count: 0, minMs: Number.POSITIVE_INFINITY, maxMs: 0, totalMs: 0, late: 0 };
}

function recordTick(stats: TickStats, elapsedMs: number, deadlineMs: number) {
  stats.count += 1;
  stats.minMs = Math.min(stats.minMs, elapsedMs);
  stats.maxMs = Math.max(stats.maxMs, elapsedMs);
  stats.totalMs += elapsedMs;
  if (elapsedMs > deadlineMs) {
    stats.late += 1;
  }
}

function mergeTickStats(allStats: TickStats[]): TickStats & { avgMs: number } {
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

function rate(count: number, elapsedMs: number): number {
  return elapsedMs > 0 ? count / (elapsedMs / 1000) : 0;
}

async function readStdin(): Promise<string> {
  const decoder = new TextDecoder();
  let body = "";
  for await (const chunk of Deno.stdin.readable) {
    body += decoder.decode(chunk);
  }
  return body;
}

declare function lockdown(options?: Record<string, unknown>): void;
declare class Compartment {
  constructor(endowments?: Record<string, unknown>, modules?: Record<string, unknown>, options?: Record<string, unknown>);
  evaluate(source: string): unknown;
}
