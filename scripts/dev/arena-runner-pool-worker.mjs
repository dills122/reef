import vm from "node:vm";
import { stdin } from "node:process";
import { pathToFileURL } from "node:url";
import { performance } from "node:perf_hooks";

const repoRoot = new URL("../../", import.meta.url).pathname;
const hostedRunner = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/hosted-runner.ts`).href);
const strategyRunner = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/strategy-runner.ts`).href);
const args = process.argv.slice(2);
const workerId = stringOption("--worker-id", "worker-0");
const compartment = stringOption("--compartment", "vm");
const loadedBots = new Map();
let startedAt = Date.now();

if (!["vm", "ses"].includes(compartment)) {
  throw new Error(`unsupported --compartment=${compartment}; expected vm or ses`);
}

if (compartment === "ses") {
  await import("ses");
  lockdown({ errorTaming: "unsafe", stackFiltering: "verbose" });
}

stdin.setEncoding("utf8");
let buffered = "";
stdin.on("data", (chunk) => {
  buffered += chunk;
  flushLines();
});
stdin.on("end", () => {
  if (buffered.trim().length > 0) {
    handleLine(buffered);
  }
});

function flushLines() {
  let newlineIndex = buffered.indexOf("\n");
  while (newlineIndex >= 0) {
    const line = buffered.slice(0, newlineIndex);
    buffered = buffered.slice(newlineIndex + 1);
    if (line.trim().length > 0) {
      handleLine(line);
    }
    newlineIndex = buffered.indexOf("\n");
  }
}

function handleLine(line) {
  let message;
  try {
    message = JSON.parse(line);
  } catch (error) {
    writeResponse({
      id: "unknown",
      ok: false,
      type: "error",
      error: { code: "invalid_json", message: error instanceof Error ? error.message : String(error) },
    });
    return;
  }
  handleMessage(message).catch((error) => {
    writeResponse({
      id: message.id ?? "unknown",
      ok: false,
      type: "error",
      error: { code: "worker_message_failed", message: error instanceof Error ? error.message : String(error) },
      resourceReport: resourceReport(),
    });
  });
}

async function handleMessage(message) {
  switch (message.type) {
    case "loadBot":
      await handleLoadBot(message);
      return;
    case "runScenario":
      await handleRunScenario(message);
      return;
    case "freezeBot":
      handleFreezeBot(message);
      return;
    case "heartbeat":
      writeResponse({
        id: message.id,
        ok: true,
        type: "heartbeat",
        workerId,
        loadedBotKeys: Array.from(loadedBots.keys()),
        resourceReport: resourceReport(),
      });
      return;
    case "shutdown":
      writeResponse({
        id: message.id,
        ok: true,
        type: "shutdown",
        workerId,
        resourceReport: resourceReport(),
      });
      process.exit(0);
      return;
    default:
      writeResponse({
        id: message.id ?? "unknown",
        ok: false,
        type: "error",
        error: { code: "unknown_message_type", message: `unknown runner message type ${message.type}` },
        resourceReport: resourceReport(),
      });
  }
}

async function handleLoadBot(message) {
  const started = performance.now();
  const result = await hostedRunner.loadHostedBotClassV1({
    source: message.source,
    fileName: message.fileName,
    ...(compartment === "vm" ? { compartmentFactory: createVmCompartmentFactory() } : {}),
    ...(message.executionLimits === undefined ? {} : { executionLimits: message.executionLimits }),
  });
  if (!result.ok) {
    writeResponse({
      id: message.id,
      ok: false,
      type: "loadBotResult",
      botKey: message.botKey,
      issues: result.issues,
      elapsedMs: performance.now() - started,
      resourceReport: resourceReport(),
    });
    return;
  }

  loadedBots.set(message.botKey, {
    botKey: message.botKey,
    fileName: message.fileName,
    BotClass: result.BotClass,
    loadedAt: new Date().toISOString(),
  });
  writeResponse({
    id: message.id,
    ok: true,
    type: "loadBotResult",
    botKey: message.botKey,
    elapsedMs: performance.now() - started,
    loadedBotCount: loadedBots.size,
    resourceReport: resourceReport(),
  });
}

async function handleRunScenario(message) {
  const loaded = loadedBots.get(message.botKey);
  if (loaded === undefined) {
    writeResponse({
      id: message.id,
      ok: false,
      type: "runScenarioResult",
      botKey: message.botKey,
      error: { code: "bot_not_loaded", message: `bot ${message.botKey} is not loaded in ${workerId}` },
      resourceReport: resourceReport(),
    });
    return;
  }

  const started = performance.now();
  const beforeCpu = process.cpuUsage();
  try {
    const report = await strategyRunner.runBotStrategyScenarioV1({
      BotClass: loaded.BotClass,
      fixture: message.fixture,
    });
    const elapsedMs = performance.now() - started;
    const cpuUsage = process.cpuUsage(beforeCpu);
    writeResponse({
      id: message.id,
      ok: report.status === "completed",
      type: "runScenarioResult",
      workerId,
      botKey: message.botKey,
      status: report.status,
      elapsedMs,
      cpuUsageMicros: cpuUsage.user + cpuUsage.system,
      summary: summarizeReport(report),
      report,
      resourceReport: resourceReport(),
    });
  } catch (error) {
    const elapsedMs = performance.now() - started;
    const cpuUsage = process.cpuUsage(beforeCpu);
    writeResponse({
      id: message.id,
      ok: false,
      type: "runScenarioResult",
      workerId,
      botKey: message.botKey,
      status: "do_not_merge",
      elapsedMs,
      cpuUsageMicros: cpuUsage.user + cpuUsage.system,
      error: { code: "scenario_execution_failed", message: error instanceof Error ? error.message : String(error) },
      resourceReport: resourceReport(),
    });
  }
}

function handleFreezeBot(message) {
  const wasLoaded = loadedBots.delete(message.botKey);
  writeResponse({
    id: message.id,
    ok: true,
    type: "freezeBotResult",
    workerId,
    botKey: message.botKey,
    frozen: wasLoaded,
    reason: message.reason ?? "unspecified",
    loadedBotCount: loadedBots.size,
    resourceReport: resourceReport(),
  });
}

function summarizeReport(report) {
  return {
    scenarioId: report.scenarioId,
    runId: report.runId,
    ticksRun: report.ticksRun,
    actionsProposed: report.actionsProposed,
    orderActionsProposed: report.orderActionsProposed,
    dataCalls: report.dataCalls,
    signalsGenerated: Number(report.signalsGenerated ?? 0),
    eventsProcessed: Number(report.eventsProcessed ?? 0),
    venueCommands: report.ticks.reduce((total, tick) => total + tick.venueCommands.length, 0),
    issues: report.issues,
    denials: report.denials,
  };
}

function resourceReport() {
  return {
    workerId,
    compartment,
    uptimeMs: Date.now() - startedAt,
    loadedBotCount: loadedBots.size,
    memoryUsage: process.memoryUsage(),
  };
}

function writeResponse(response) {
  process.stdout.write(`${JSON.stringify(response)}\n`);
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

function stringOption(name, fallback) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? fallback : arg.slice(name.length + 1);
}
