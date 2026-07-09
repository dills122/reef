import vm from "node:vm";
import { stdin } from "node:process";
import { pathToFileURL } from "node:url";
import { performance } from "node:perf_hooks";

const repoRoot = new URL("../../", import.meta.url).pathname;
const hostedRunner = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/hosted-runner.ts`).href);
const strategyRunner = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/strategy-runner.ts`).href);
const harness = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/harness.ts`).href);
const venueAdapter = await import(pathToFileURL(`${repoRoot}packages/bot-sdk/src/venue-adapter.ts`).href);
const args = process.argv.slice(2);
const workerId = stringOption("--worker-id", "worker-0");
const compartment = stringOption("--compartment", "vm");
const loadedBots = new Map();
const sessions = new Map();
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
    case "startSession":
      await handleStartSession(message);
      return;
    case "runTick":
      await handleRunTick(message);
      return;
    case "replaceOrders":
      handleReplaceOrders(message);
      return;
    case "stopSession":
      await handleStopSession(message);
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

function handleReplaceOrders(message) {
  const session = sessions.get(message.sessionId);
  if (session === undefined) {
    writeResponse({
      id: message.id,
      ok: false,
      type: "replaceOrdersResult",
      workerId,
      sessionId: message.sessionId,
      error: { code: "session_not_started", message: `session ${message.sessionId} is not active in ${workerId}` },
      resourceReport: resourceReport(),
    });
    return;
  }
  session.orderState.clear();
  for (const order of message.orders ?? []) {
    session.orderState.set(order.orderId, order);
    session.orderHistory.set(order.orderId, order);
  }
  writeResponse({
    id: message.id,
    ok: true,
    type: "replaceOrdersResult",
    workerId,
    sessionId: message.sessionId,
    orderCount: session.orderState.size,
    resourceReport: resourceReport(),
  });
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

async function handleStartSession(message) {
  const loaded = loadedBots.get(message.botKey);
  if (loaded === undefined) {
    writeResponse({
      id: message.id,
      ok: false,
      type: "startSessionResult",
      workerId,
      sessionId: message.sessionId,
      botKey: message.botKey,
      error: { code: "bot_not_loaded", message: `bot ${message.botKey} is not loaded in ${workerId}` },
      resourceReport: resourceReport(),
    });
    return;
  }
  const started = performance.now();
  const bot = new loaded.BotClass();
  const orderState = new Map();
  const orderHistory = new Map();
  for (const order of message.fixture?.initialOrders ?? []) {
    orderState.set(order.orderId, order);
    orderHistory.set(order.orderId, order);
  }
  const session = {
    sessionId: message.sessionId,
    botKey: message.botKey,
    bot,
    fixture: message.fixture,
    policy: { ...harness.defaultBotRuntimePolicyV1, ...(message.fixture?.policy ?? {}) },
    logs: [],
    denials: [],
    orderState,
    orderHistory,
    commandSequence: 1,
    tickIndex: 0,
    counters: {
      ticksRun: 0,
      actionsProposed: 0,
      orderActionsProposed: 0,
      dataCalls: 0,
      venueCommands: 0,
      issues: [],
    },
  };
  try {
    await bot.onStart(contextForSession(session));
    sessions.set(message.sessionId, session);
    writeResponse({
      id: message.id,
      ok: true,
      type: "startSessionResult",
      workerId,
      sessionId: message.sessionId,
      botKey: message.botKey,
      elapsedMs: performance.now() - started,
      resourceReport: resourceReport(),
    });
  } catch (error) {
    writeResponse({
      id: message.id,
      ok: false,
      type: "startSessionResult",
      workerId,
      sessionId: message.sessionId,
      botKey: message.botKey,
      elapsedMs: performance.now() - started,
      error: { code: "session_start_failed", message: error instanceof Error ? error.message : String(error) },
      resourceReport: resourceReport(),
    });
  }
}

async function handleRunTick(message) {
  const session = sessions.get(message.sessionId);
  if (session === undefined) {
    writeResponse({
      id: message.id,
      ok: false,
      type: "runTickResult",
      workerId,
      sessionId: message.sessionId,
      error: { code: "session_not_started", message: `session ${message.sessionId} is not active in ${workerId}` },
      resourceReport: resourceReport(),
    });
    return;
  }
  const started = performance.now();
  const beforeCpu = process.cpuUsage();
  const tick = message.tick;
  const tickIndex = session.tickIndex;
  const tickCounters = { dataCalls: 0, dataCallsThisTick: 0 };
  const context = contextForSession(session, tick, tickCounters);
  try {
    const actions = Array.from(await session.bot.onTick(context));
    const expandedActions = Array.from(venueAdapter.expandCancelAllActionsV1(actions, Array.from(session.orderState.values())));
    const orderActions = expandedActions.filter((action) => action.type !== "noop").length;
    const venueResult = venueAdapter.toVenueCommandRequestsV1(
      expandedActions,
      venueContextForSession(session, tick, session.commandSequence),
    );
    const venueCommands = venueResult.ok ? Array.from(venueResult.value) : [];
    const tickIssues = [];
    if (!venueResult.ok) {
      session.denials.push(venueResult.denial);
      tickIssues.push({ code: "venue_adapter_denial", message: venueResult.denial.message, tick: tickIndex });
    } else {
      applyActionsToOrderState(expandedActions, session, tickIndex);
      session.commandSequence += expandedActions.filter((action) => action.type !== "noop").length;
    }

    session.tickIndex += 1;
    session.counters.ticksRun += 1;
    session.counters.actionsProposed += actions.length;
    session.counters.orderActionsProposed += orderActions;
    session.counters.dataCalls += tickCounters.dataCalls;
    session.counters.venueCommands += venueCommands.length;
    session.counters.issues.push(...tickIssues);
    const elapsedMs = performance.now() - started;
    const cpuUsage = process.cpuUsage(beforeCpu);
    writeResponse({
      id: message.id,
      ok: tickIssues.length === 0,
      type: "runTickResult",
      workerId,
      sessionId: session.sessionId,
      botKey: session.botKey,
      tick: tickIndex,
      elapsedMs,
      cpuUsageMicros: cpuUsage.user + cpuUsage.system,
      actions: expandedActions,
      venueCommands,
      denials: tickIssues.length === 0 ? [] : session.denials.slice(-tickIssues.length),
      issues: tickIssues,
      dataCalls: tickCounters.dataCalls,
      ordersAfterTick: Array.from(session.orderState.values()),
      resourceReport: resourceReport(),
    });
  } catch (error) {
    const elapsedMs = performance.now() - started;
    const cpuUsage = process.cpuUsage(beforeCpu);
    const issue = { code: "tick_execution_failed", message: error instanceof Error ? error.message : String(error), tick: tickIndex };
    session.counters.issues.push(issue);
    writeResponse({
      id: message.id,
      ok: false,
      type: "runTickResult",
      workerId,
      sessionId: session.sessionId,
      botKey: session.botKey,
      tick: tickIndex,
      elapsedMs,
      cpuUsageMicros: cpuUsage.user + cpuUsage.system,
      actions: [],
      venueCommands: [],
      denials: [],
      issues: [issue],
      dataCalls: 0,
      ordersAfterTick: Array.from(session.orderState.values()),
      resourceReport: resourceReport(),
    });
  }
}

async function handleStopSession(message) {
  const session = sessions.get(message.sessionId);
  if (session === undefined) {
    writeResponse({
      id: message.id,
      ok: false,
      type: "stopSessionResult",
      workerId,
      sessionId: message.sessionId,
      error: { code: "session_not_started", message: `session ${message.sessionId} is not active in ${workerId}` },
      resourceReport: resourceReport(),
    });
    return;
  }
  const started = performance.now();
  try {
    await session.bot.onStop(contextForSession(session));
    sessions.delete(message.sessionId);
    writeResponse({
      id: message.id,
      ok: true,
      type: "stopSessionResult",
      workerId,
      sessionId: session.sessionId,
      botKey: session.botKey,
      elapsedMs: performance.now() - started,
      summary: {
        ...session.counters,
        finalOrders: Array.from(session.orderState.values()),
      },
      resourceReport: resourceReport(),
    });
  } catch (error) {
    sessions.delete(message.sessionId);
    writeResponse({
      id: message.id,
      ok: false,
      type: "stopSessionResult",
      workerId,
      sessionId: session.sessionId,
      botKey: session.botKey,
      elapsedMs: performance.now() - started,
      error: { code: "session_stop_failed", message: error instanceof Error ? error.message : String(error) },
      summary: session.counters,
      resourceReport: resourceReport(),
    });
  }
}

function handleFreezeBot(message) {
  const wasLoaded = loadedBots.delete(message.botKey);
  for (const [sessionId, session] of sessions.entries()) {
    if (session.botKey === message.botKey) {
      sessions.delete(sessionId);
    }
  }
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
    activeSessionCount: sessions.size,
    memoryUsage: process.memoryUsage(),
  };
}

function contextForSession(session, tick, counters = { dataCalls: 0, dataCallsThisTick: 0 }) {
  return harness.createFixtureBotContextV1({
    policy: session.policy,
    nowIso: tick?.occurredAt,
    fixtureData: {
      marketSnapshots: tick?.marketSnapshots,
      historicalBars: {
        ...(session.fixture?.historicalBars ?? {}),
        ...(tick?.historicalBarsClosed ?? {}),
      },
      currentOrders: Array.from(session.orderState.values()),
      orderHistory: Array.from(session.orderHistory.values()),
      config: session.fixture?.config ?? {},
    },
    logs: session.logs,
    denials: session.denials,
    counters,
  });
}

function venueContextForSession(session, tick, startingSequence) {
  return {
    scenarioId: session.fixture?.scenarioId,
    runId: session.fixture?.runId ?? session.sessionId,
    runKind: session.fixture?.runKind ?? "arena-local",
    venueSessionId: session.fixture?.venueSessionId ?? "arena-local-session",
    clientId: session.fixture?.clientId,
    actorId: session.fixture?.actorId ?? `actor-${session.botKey}`,
    participantId: session.fixture?.participantId ?? `participant-${session.botKey}`,
    accountId: session.fixture?.accountId ?? `account-${session.botKey}`,
    botId: session.fixture?.botId ?? session.botKey,
    botVersion: session.fixture?.botVersion ?? "local-fixture",
    correlationId: session.fixture?.correlationId ?? `${session.sessionId}-corr`,
    occurredAt: tick?.occurredAt ?? new Date().toISOString(),
    commandIdPrefix: `${session.sessionId}-cmd`,
    traceIdPrefix: `${session.sessionId}-trace`,
    idempotencyKeyPrefix: `${session.sessionId}-idem`,
    startingSequence,
  };
}

function applyActionsToOrderState(actions, session, tickIndex) {
  let sequence = session.commandSequence;
  for (const action of actions) {
    if (action.type === "noop") {
      sequence += 1;
      continue;
    }
    if (action.type === "submit_limit") {
      const orderId = action.order.clientOrderId ?? `${session.sessionId}-cmd-order-${sequence}`;
      const order = {
        orderId,
        instrumentId: action.order.instrumentId,
        side: action.order.side,
        quantity: action.order.quantity,
        remainingQuantity: action.order.quantity,
        limitPrice: action.order.limitPrice,
        status: "OPEN",
      };
      session.orderState.set(orderId, order);
      session.orderHistory.set(orderId, order);
    } else if (action.type === "cancel_order") {
      const existing = session.orderState.get(action.order.orderId);
      if (existing !== undefined) {
        const updated = { ...existing, remainingQuantity: 0, status: "CANCELED" };
        session.orderState.delete(action.order.orderId);
        session.orderHistory.set(action.order.orderId, updated);
      }
    } else if (action.type === "modify_order") {
      const existing = session.orderState.get(action.order.orderId);
      if (existing !== undefined) {
        const updated = {
          ...existing,
          quantity: action.order.quantity ?? existing.quantity,
          remainingQuantity: action.order.quantity ?? existing.remainingQuantity,
          limitPrice: action.order.limitPrice ?? existing.limitPrice,
        };
        session.orderState.set(action.order.orderId, updated);
        session.orderHistory.set(action.order.orderId, updated);
      }
    }
    sequence += 1;
  }
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
