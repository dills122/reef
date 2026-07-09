import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { env, loadDotEnv, sleep, waitForHttp } from "./lib/dev-utils.mjs";
import { writeJsonFileStreaming } from "./lib/large-json-writer.mjs";
import { createOpenBaoRuntimeSecretProvider, runtimeConfigPreflightReport } from "./lib/openbao-runtime-config.mjs";

loadDotEnv();

const repoRoot = new URL("../../", import.meta.url).pathname;
const resolveBotRuntimeConfig = await loadRuntimeConfigResolver();
const args = process.argv.slice(2);
const config = {
  runId: stringOption("--run-id", `arena-local-tick-${Date.now()}`),
  mode: stringOption("--mode", "packages/scenario-definitions/arena/equity-sprint.v1.json"),
  extraBots: csvOption("--extra-bots", ""),
  expectFreezeBots: csvOption("--expect-freeze-bots", ""),
  compartment: stringOption("--compartment", "ses"),
  submitMode: stringOption("--submit-mode", "dry-run"),
  venueUrl: stringOption("--venue-url", env("BOT_SDK_VENUE_URL", env("RUNTIME_BASE_URL", ""))),
  arenaAdminUrl: stringOption("--arena-admin-url", env("ARENA_ADMIN_API_URL", env("BOT_SDK_VENUE_URL", env("RUNTIME_BASE_URL", "")))),
  openBaoAddr: stringOption("--openbao-addr", env("OPENBAO_ADDR", env("VAULT_ADDR", ""))),
  openBaoToken: stringOption("--openbao-token", env("OPENBAO_TOKEN", env("VAULT_TOKEN", ""))),
  seedReference: args.includes("--seed-reference"),
  persistResults: args.includes("--persist-results"),
  actorId: stringOption("--actor-id", env("ADMIN_ACTOR_ID", "admin-cli")),
  commandTimeoutMs: numberOption("--command-timeout-ms", 15000),
  commandPollMs: numberOption("--command-poll-ms", 250),
  commandWaitMode: stringOption("--command-wait-mode", "terminal"),
  projectionDrainTimeoutMs: numberOption("--projection-drain-timeout-ms", 0),
  projectionDrainPollMs: numberOption("--projection-drain-poll-ms", 500),
  durationSeconds: numberOption("--duration-seconds", 0),
  tickIntervalMs: numberOption("--tick-interval-ms", 0),
  warmupSeconds: numberOption("--warmup-seconds", 0),
  healthSampleIntervalMs: numberOption("--health-sample-interval-ms", 0),
  requireProjectionDrain: args.includes("--require-projection-drain"),
  paceTicks: args.includes("--pace-ticks"),
  out: stringOption("--out", "/tmp/reef-arena-local-tick-run.json"),
  reportShape: stringOption("--report-shape", "full"),
};

if (!["vm", "ses"].includes(config.compartment)) {
  throw new Error(`unsupported --compartment=${config.compartment}; expected vm or ses`);
}
if (!["dry-run", "live"].includes(config.submitMode)) {
  throw new Error(`unsupported --submit-mode=${config.submitMode}; expected dry-run or live`);
}
if (!["terminal", "accepted", "none"].includes(config.commandWaitMode)) {
  throw new Error(`unsupported --command-wait-mode=${config.commandWaitMode}; expected terminal, accepted, or none`);
}
if (!["full", "compact"].includes(config.reportShape)) {
  throw new Error(`unsupported --report-shape=${config.reportShape}; expected full or compact`);
}
if (config.submitMode === "live" && config.venueUrl.length === 0) {
  throw new Error("--venue-url or BOT_SDK_VENUE_URL is required when --submit-mode=live");
}
if (config.persistResults && config.arenaAdminUrl.length === 0) {
  throw new Error("--arena-admin-url, ARENA_ADMIN_API_URL, --venue-url, or BOT_SDK_VENUE_URL is required when --persist-results is set");
}

const mode = readJson(config.mode);
const catalog = readJson(mode.catalogPath);
const riskProfiles = catalog.riskProfiles ?? {};
const selectedBots = [...mode.botRefs, ...config.extraBots].map((botId) => {
  const entry = catalog.bots.find((bot) => bot.botId === botId);
  if (entry === undefined) {
    throw new Error(`mode references unknown bot ${botId}`);
  }
  const riskProfile = riskProfiles[entry.riskProfile];
  if (riskProfile === undefined) {
    throw new Error(`bot ${botId} references unknown risk profile ${entry.riskProfile}`);
  }
  return { ...entry, riskProfileName: entry.riskProfile, catalogVersionId: entry.versionId, versionId: localVersionId(entry), riskProfile };
});
const baseFixture = readJson("packages/bot-sdk/fixtures/aapl-multi-tick.json");
const runPlan = buildRunPlan(mode, config, baseFixture, selectedBots);

const outDir = mkdtempSync(join(tmpdir(), "reef-arena-local-tick-"));
let worker;

async function main() {
  worker = new RunnerWorker("arena-tick-worker-0", config.compartment);
  const startedAt = performance.now();
  const startedAtIso = new Date().toISOString();

  try {
    if (config.submitMode === "live") {
      await waitForHttp(`${config.venueUrl.replace(/\/$/, "")}/health`, 120);
      if (config.seedReference) {
        await seedReferenceData(selectedBots);
      }
    }
    await worker.start();
    const bots = [];
    for (const bot of selectedBots) {
      bots.push({
        ...bot,
        identityKey: botIdentityKey(bot),
        artifact: buildArtifact(bot.entryPath, bot.botId),
        runtimeConfigPreflight: await resolveRuntimeConfigForBot(bot),
      });
    }
    for (const bot of bots) {
      const load = await worker.request({
        type: "loadBot",
        botKey: bot.botId,
        source: bot.artifact.source,
        fileName: bot.artifact.fileName,
        executionLimits: {
          tickTimeoutMs: bot.riskProfile.maxTickLatencyMs,
          lifecycleTimeoutMs: bot.riskProfile.maxTickLatencyMs,
        },
      });
      if (!load.ok) {
        throw new Error(`loadBot failed for ${bot.botId}: ${JSON.stringify(load.issues ?? load.error)}`);
      }
    }

    const healthSamples = [];
    const sessionReports = await runArenaSessions(bots, healthSamples);
    const enforcementEvents = sessionReports.flatMap((result) => enforceBot(result.bot, result));

    const botResults = scoreBots(sessionReports, enforcementEvents);
    const venueReadback = await collectVenueReadback(botResults);
    const completedAtIso = new Date().toISOString();
    const report = buildReport({
      botResults,
      enforcementEvents,
      sessionReports,
      healthSamples,
      venueReadback,
      startedAt: startedAtIso,
      completedAt: completedAtIso,
      elapsedMs: performance.now() - startedAt,
    });
    report.persistence = await persistArenaResults(report);
    await writeJsonFileStreaming(config.out, reportForOutput(report), { space: 2 });
    assertExpectedFreezeBots(report);
    console.log(`arena local tick run complete: ${resolve(config.out)}`);
    console.log(
      `status=${report.status} bots=${botResults.length} ticks=${report.totals.ticks} venueCommands=${report.totals.venueCommands} submitted=${report.totals.submittedCommands} freezes=${enforcementEvents.filter((event) => event.decision === "freeze").length}`,
    );
    for (const entry of report.leaderboard) {
      console.log(`rank=${entry.rank} bot=${entry.botId} score=${entry.score} disqualified=${entry.disqualified}`);
    }
  } finally {
    if (worker !== undefined) {
      await worker.shutdown();
    }
  }
}

async function runArenaSessions(bots, healthSamples) {
  const sessions = [];
  for (const [botIndex, bot] of bots.entries()) {
    const schedulingClass = botSchedulingClass(bot);
    const expectedTicks = ticksForSchedulingClass(schedulingClass, botScheduleIntervalMs(bot));
    console.log(`arena bot start ${botIndex + 1}/${bots.length}: ${bot.botId} class=${schedulingClass} ticks=${expectedTicks}`);
    sessions.push(await startBotSession(bot));
  }

  for (const event of schedulerEvents(sessions)) {
    const scheduledTicks = event.sessions.flatMap((session) => {
      if (!session.start.ok || session.stoppedForPolicy) {
        return [];
      }
      const tickIndex = event.tickIndexBySessionId.get(session.sessionId);
      const tick = session.scheduled[tickIndex];
      if (tick === undefined) {
        return [];
      }
      return [{ session, tick, tickIndex }];
    });
    const tickReports = await Promise.all(scheduledTicks.map(({ session, tick, tickIndex }) =>
      runSessionTick(session, tick, tickIndex, event.offsetMs).then((tickReport) => ({ session, tickReport })),
    ));
    for (const { session, tickReport } of tickReports) {
      session.ticks.push(tickReport);
    }
    if (event.sampleHealth) {
      await maybeCollectHealthSample(healthSamples, event.healthSampleIndex, tickReports.map((result) => result.tickReport), event.offsetMs);
    }
    if (config.paceTicks && event.nextOffsetMs !== null) {
      await sleep(event.nextOffsetMs - event.offsetMs);
    }
  }

  for (const session of sessions) {
    if (session.start.ok) {
      session.stop = await worker.request({ type: "stopSession", sessionId: session.sessionId });
    }
    console.log(`arena bot complete ${sessions.indexOf(session) + 1}/${sessions.length}: ${session.bot.botId} ticks=${session.ticks.length}`);
  }

  return sessions.map(({ scheduled, stoppedForPolicy, ...session }) => session);
}

async function startBotSession(bot) {
  const schedulingClass = botSchedulingClass(bot);
  const intervalMs = botScheduleIntervalMs(bot);
  const tickCount = ticksForSchedulingClass(schedulingClass, intervalMs);
  const fixture = fixtureForBot(bot, { tickCount, tickIntervalMs: intervalMs });
  const sessionId = `${mode.modeId}-${bot.identityKey}-${Date.now()}`;
  const start = await worker.request({ type: "startSession", botKey: bot.botId, sessionId, fixture });
  if (!start.ok) {
    return { bot, sessionId, schedulingClass, intervalMs, start, scheduled: [], ticks: [], stop: undefined, stoppedForPolicy: true };
  }
  const scheduled = fixture.ticks.slice(0, tickCount);
  return { bot, sessionId, schedulingClass, intervalMs, start, scheduled, ticks: [], stop: undefined, stoppedForPolicy: false };
}

function schedulerEvents(sessions) {
  const eventsByOffset = new Map();
  for (const session of sessions) {
    for (let tickIndex = 0; tickIndex < session.scheduled.length; tickIndex += 1) {
      const offsetMs = tickIndex * session.intervalMs;
      if (offsetMs >= runPlan.durationMs) {
        continue;
      }
      const event = eventsByOffset.get(offsetMs) ?? {
        offsetMs,
        sessions: [],
        tickIndexBySessionId: new Map(),
        sampleHealth: offsetMs % runPlan.healthSampleIntervalMs === 0,
        healthSampleIndex: Math.floor(offsetMs / runPlan.healthSampleIntervalMs),
        nextOffsetMs: null,
      };
      event.sessions.push(session);
      event.tickIndexBySessionId.set(session.sessionId, tickIndex);
      eventsByOffset.set(offsetMs, event);
    }
  }
  const events = Array.from(eventsByOffset.values()).sort((left, right) => left.offsetMs - right.offsetMs);
  for (let index = 0; index < events.length; index += 1) {
    events[index].nextOffsetMs = events[index + 1]?.offsetMs ?? null;
  }
  return events;
}

function botSchedulingClass(bot) {
  if (typeof bot.schedulingClass === "string" && bot.schedulingClass.length > 0) {
    return bot.schedulingClass;
  }
  if (bot.role === "market-maker" || bot.riskProfileName === "house_liquidity") {
    return "house_responsive";
  }
  if (bot.role === "npc") {
    return "npc_tick";
  }
  return "contestant_tick";
}

function botScheduleIntervalMs(bot) {
  if (botSchedulingClass(bot) === "house_responsive") {
    return positiveNumber(houseLiquidityConfig(bot).wakeIntervalMs, runPlan.houseWakeIntervalMs);
  }
  return runPlan.tickIntervalMs;
}

function houseLiquidityConfig(bot) {
  return {
    ...(mode.houseLiquidityDefaults ?? {}),
    ...(bot.houseLiquidity ?? {}),
  };
}

function ticksForSchedulingClass(schedulingClass, intervalMs = runPlan.tickIntervalMs) {
  if (schedulingClass === "house_responsive") {
    return Math.max(1, Math.ceil(runPlan.durationMs / intervalMs));
  }
  return runPlan.tickCount;
}

async function runSessionTick(session, tick, tickIndex, offsetMs) {
  const orderRefresh = await refreshLiveSessionOrders(session);
  if (orderRefresh !== undefined && !orderRefresh.ok) {
    return {
      tick,
      schedulingClass: session.schedulingClass,
      offsetMs,
      ok: false,
      issues: [orderRefresh.issue],
      venueCommands: [],
      submission: emptySubmission("live_order_refresh_failed"),
    };
  }
  const tickResult = await worker.request({ type: "runTick", sessionId: session.sessionId, tick });
  const policyViolation = tickPolicyViolation(session, tickResult, offsetMs);
  if (policyViolation !== undefined) {
    session.stoppedForPolicy = true;
    if (policyViolation.operational) {
      return {
        ...tickResult,
        tick,
        schedulingClass: session.schedulingClass,
        offsetMs,
        ok: tickResult.ok ?? true,
        operationalControl: policyViolation,
        issues: [...(tickResult.issues ?? []), policyViolation],
        venueCommands: [],
        submission: emptySubmission("operational_control"),
      };
    }
    return {
      ...tickResult,
      tick,
      ok: false,
      policyViolation,
      issues: [...(tickResult.issues ?? []), policyViolation],
      venueCommands: [],
      submission: emptySubmission("policy_violation"),
    };
  }
  const tickReport = {
    ...tickResult,
    tick,
    schedulingClass: session.schedulingClass,
    offsetMs,
    submission: await submitVenueCommands(tickResult.venueCommands ?? []),
  };
  if (tickIndex === 0 || (tickIndex + 1) % 10 === 0 || tickIndex + 1 === session.scheduled.length) {
    console.log(`arena bot progress ${session.bot.botId}: tick=${tickIndex + 1}/${session.scheduled.length} commands=${tickReport.submission?.submitted ?? 0} timedOut=${tickReport.submission?.timedOut ?? 0}`);
  }
  return tickReport;
}

function tickPolicyViolation(session, tickResult, offsetMs) {
  const bot = session.bot;
  const actionCount = tickResult.actions?.length ?? 0;
  if (actionCount > bot.riskProfile.maxActionsPerTick) {
    return {
      code: "max_actions_per_tick",
      message: `actions ${actionCount} > ${bot.riskProfile.maxActionsPerTick}`,
      observed: actionCount,
      limit: bot.riskProfile.maxActionsPerTick,
    };
  }
  if (session.schedulingClass === "house_responsive") {
    const houseConfig = houseLiquidityConfig(bot);
    const submittedCommands = tickResult.venueCommands?.length ?? 0;
    const maxCommandsPerSecond = Number(houseConfig.maxCommandsPerSecond ?? 0);
    if (maxCommandsPerSecond > 0) {
      const second = Math.floor(offsetMs / 1000);
      session.houseCommandCountsBySecond ??= new Map();
      const nextCount = Number(session.houseCommandCountsBySecond.get(second) ?? 0) + submittedCommands;
      session.houseCommandCountsBySecond.set(second, nextCount);
      if (nextCount > maxCommandsPerSecond) {
        return {
          code: "house_max_commands_per_second",
          message: `house commands ${nextCount} > ${maxCommandsPerSecond} in second ${second}`,
          observed: nextCount,
          limit: maxCommandsPerSecond,
          operational: true,
        };
      }
    }
    const cancelCommands = (tickResult.actions ?? []).filter((action) => action.type === "cancel_order").length;
    const maxCancelsPerSecond = Number(houseConfig.maxCancelsPerSecond ?? 0);
    if (maxCancelsPerSecond > 0) {
      const second = Math.floor(offsetMs / 1000);
      session.houseCancelCountsBySecond ??= new Map();
      const nextCount = Number(session.houseCancelCountsBySecond.get(second) ?? 0) + cancelCommands;
      session.houseCancelCountsBySecond.set(second, nextCount);
      if (nextCount > maxCancelsPerSecond) {
        return {
          code: "house_max_cancels_per_second",
          message: `house cancels ${nextCount} > ${maxCancelsPerSecond} in second ${second}`,
          observed: nextCount,
          limit: maxCancelsPerSecond,
          operational: true,
        };
      }
    }
    const maxOpenOrdersPerSide = Number(houseConfig.maxOpenOrdersPerSide ?? 0);
    if (maxOpenOrdersPerSide > 0) {
      const counts = openOrderCountsByInstrumentSide(tickResult.ordersAfterTick ?? []);
      for (const count of counts) {
        if (count.openOrders > maxOpenOrdersPerSide) {
          return {
            code: "house_max_open_orders_per_side",
            message: `house open orders ${count.openOrders} > ${maxOpenOrdersPerSide} for ${count.instrumentId} ${count.side}`,
            observed: count.openOrders,
            limit: maxOpenOrdersPerSide,
            operational: true,
          };
        }
      }
    }
  }
  return undefined;
}

async function refreshLiveSessionOrders(session) {
  if (config.submitMode !== "live" || session.schedulingClass !== "house_responsive") {
    return undefined;
  }
  const participantId = participantIdForBot(session.bot);
  const response = await getJson(
    `${config.venueUrl.replace(/\/$/, "")}/api/v1/orders/current?participantId=${encodeURIComponent(participantId)}&limit=100`,
    readbackHeaders(participantId),
  );
  if (response.statusCode < 200 || response.statusCode >= 300) {
    return {
      ok: false,
      issue: {
        code: "live_order_refresh_failed",
        message: `live order refresh failed for ${session.bot.botId}: HTTP ${response.statusCode}`,
      },
    };
  }
  const orders = Array.isArray(response.body?.orders) ? response.body.orders.map(toOwnOrder) : [];
  const replace = await worker.request({ type: "replaceOrders", sessionId: session.sessionId, orders });
  if (!replace.ok) {
    return {
      ok: false,
      issue: {
        code: "live_order_refresh_failed",
        message: `live order refresh failed for ${session.bot.botId}: ${JSON.stringify(replace.error ?? replace)}`,
      },
    };
  }
  return { ok: true };
}

function toOwnOrder(order) {
  const limitPrice = priceFromNanos(order.limitPrice);
  return {
    orderId: String(order.orderId ?? ""),
    instrumentId: String(order.instrumentId ?? ""),
    side: order.side === "SELL" ? "SELL" : "BUY",
    quantity: numberValue(order.quantityUnits),
    remainingQuantity: numberValue(order.remainingQuantityUnits),
    ...(limitPrice === undefined ? {} : { limitPrice }),
    status: ownOrderStatus(order.status),
  };
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function priceFromNanos(value) {
  if (value === undefined || value === null || value === "") {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed / 1_000_000_000 : undefined;
}

function ownOrderStatus(status) {
  if (status === "OPEN" || status === "PARTIALLY_FILLED" || status === "FILLED" || status === "CANCELED" || status === "REJECTED") {
    return status;
  }
  if (status === "CANCELLED") {
    return "CANCELED";
  }
  return "REJECTED";
}

function openOrderCountsByInstrumentSide(orders) {
  const counts = new Map();
  for (const order of orders) {
    if ((order.status !== "OPEN" && order.status !== "PARTIALLY_FILLED") || Number(order.remainingQuantity ?? 0) <= 0) {
      continue;
    }
    const key = `${order.instrumentId}:${order.side}`;
    counts.set(key, {
      instrumentId: order.instrumentId,
      side: order.side,
      openOrders: Number(counts.get(key)?.openOrders ?? 0) + 1,
    });
  }
  return Array.from(counts.values());
}

function enforceBot(bot, session) {
  const events = [];
  const counters = sessionCounters(session);
  const operationalControls = session.ticks
    .map((tick) => tick.operationalControl)
    .filter((control) => control !== undefined);
  if (operationalControls.length > 0) {
    events.push({
      type: "arena.houseOperationalControl.v0",
      runId: config.runId,
      botId: bot.botId,
      versionId: bot.versionId,
      decision: "operational_pause",
      reasonCode: operationalControls[0].code,
      reason: operationalControls.map((control) => control.message).join("; "),
      policyVersion: mode.riskPolicyVersion,
      counters,
      occurredAt: new Date().toISOString(),
    });
    if (botSchedulingClass(bot) === "house_responsive") {
      return events;
    }
  }
  const reasons = [];
  if (!session.start.ok) {
    reasons.push("session_start_failed");
  }
  if (counters.failedTicks > 0) {
    reasons.push(`failedTicks ${counters.failedTicks} > 0`);
  }
  if (counters.failedCommands > 0) {
    reasons.push(`failedCommands ${counters.failedCommands} > 0`);
  }
  if (counters.timedOutCommands > 0) {
    reasons.push(`timedOutCommands ${counters.timedOutCommands} > 0`);
  }
  if (counters.maxActionsPerTick > bot.riskProfile.maxActionsPerTick) {
    reasons.push(`maxActionsPerTick ${counters.maxActionsPerTick} > ${bot.riskProfile.maxActionsPerTick}`);
  }
  if (counters.latencyP95Ms > bot.riskProfile.maxTickLatencyMs) {
    reasons.push(`latencyP95Ms ${counters.latencyP95Ms.toFixed(2)} > ${bot.riskProfile.maxTickLatencyMs}`);
  }
  if (reasons.length === 0) {
    return events;
  }
  events.push({
    type: "arena.enforcement.v0",
    runId: config.runId,
    botId: bot.botId,
    versionId: bot.versionId,
    decision: "freeze",
    reasonCode: "tick_policy_violation",
    reason: reasons.join("; "),
    policyVersion: mode.riskPolicyVersion,
    counters,
    occurredAt: new Date().toISOString(),
  });
  return events;
}

function scoreBots(sessionReports, enforcementEvents) {
  return sessionReports.map((session) => {
    const counters = sessionCounters(session);
    const freezeCount = enforcementEvents.filter((event) => event.botId === session.bot.botId && event.decision === "freeze").length;
    const operationalPauseCount = enforcementEvents.filter((event) => event.botId === session.bot.botId && event.decision === "operational_pause").length;
    const score = Math.max(
      0,
      1_000_000 + counters.venueCommands * 250 + counters.actions * 25 - counters.failedTicks * 100_000 - freezeCount * 250_000,
    );
    return {
      botId: session.bot.botId,
      displayName: displayNameForBot(session.bot),
      versionId: session.bot.versionId,
      runnerKey: session.bot.runnerKey,
      role: session.bot.role,
      schedulingClass: botSchedulingClass(session.bot),
      riskProfile: session.bot.riskProfile === undefined ? undefined : session.bot.riskProfile,
      runtimeConfigPreflight: session.bot.runtimeConfigPreflight.report,
      scoreEligible: session.bot.scoreEligible,
      publicLeaderboard: session.bot.publicLeaderboard,
      ticksRun: counters.ticks,
      actionsProposed: counters.actions,
      venueCommands: counters.venueCommands,
      dataCalls: counters.dataCalls,
      failedTicks: counters.failedTicks,
      latencyP95Ms: counters.latencyP95Ms,
      freezeCount,
      operationalPauseCount,
      disqualified: freezeCount > 0,
      score,
      tradingMetrics: summarizeTradingMetrics(session, counters),
    };
  });
}

function displayNameForBot(bot) {
  return typeof bot.displayName === "string" && bot.displayName.trim().length > 0
    ? bot.displayName.trim()
    : bot.botId;
}

function scoringAssumptions() {
  return {
    schemaVersion: "reef.arena.scoringAssumptions.v0",
    scoringPolicyVersion: mode.scoringPolicyVersion,
    scoreBasis: "participation-and-policy-compliance",
    leaderboardScope: "score-eligible public competitor bots only",
    houseBots: "diagnostics-only; excluded from public leaderboard and not treated as bad actors when supplying configured liquidity",
    pnl: {
      status: "not-yet-scored",
      basis: "report-only synthetic placeholders until execution attribution lands",
      markPriceSource: "future final mid/last/reference mark",
      fees: "zero placeholder until maker/taker fee policy is configured",
    },
    tradingMetrics: {
      status: "command-mix v0",
      source: "runner pre-submit venue commands and command status summaries",
      fillsAndExecutions: "unavailable until command result execution attribution is added to arena reports",
    },
  };
}

function summarizeTradingMetrics(session, counters) {
  const byRoute = {};
  const submittedByRoute = {};
  const byInstrument = {};
  const bySide = {};
  let buyQuantity = 0;
  let sellQuantity = 0;
  let grossSubmittedQuantity = 0;
  let grossSubmittedNotional = 0;

  for (const tick of session.ticks) {
    for (const command of tick.venueCommands ?? []) {
      increment(byRoute, command.route ?? "unknown");
      const body = command.body ?? {};
      const instrumentId = typeof body.instrumentId === "string" && body.instrumentId.length > 0 ? body.instrumentId : "unknown";
      const side = body.side === "SELL" ? "SELL" : body.side === "BUY" ? "BUY" : "unknown";

      if (command.route === "/api/v1/orders/submit" || command.route === "/api/v1/orders/modify") {
        increment(byInstrument, instrumentId);
        increment(bySide, side);
      }

      if (command.route === "/api/v1/orders/submit") {
        const quantity = numberValue(body.quantityUnits);
        const price = priceFromNanos(body.limitPrice);
        grossSubmittedQuantity += quantity;
        if (side === "BUY") buyQuantity += quantity;
        if (side === "SELL") sellQuantity += quantity;
        if (price !== undefined) {
          grossSubmittedNotional += quantity * price;
        }
      }
    }
    for (const command of tick.submission?.commands ?? []) {
      increment(submittedByRoute, command.route ?? "unknown");
    }
  }

  return {
    schemaVersion: "reef.arena.tradingMetrics.v0",
    basis: "report-only command mix; PnL/fill attribution pending",
    commands: {
      proposed: counters.venueCommands,
      submitted: counters.submittedCommands,
      completed: counters.completedCommands,
      failed: counters.failedCommands,
      rejected: counters.rejectedCommands,
      timedOut: counters.timedOutCommands,
      byRoute: sortedRecord(byRoute),
      submittedByRoute: sortedRecord(submittedByRoute),
    },
    orderFlow: {
      submittedLimitOrders: Number(byRoute["/api/v1/orders/submit"] ?? 0),
      modifyCommands: Number(byRoute["/api/v1/orders/modify"] ?? 0),
      cancelCommands: Number(byRoute["/api/v1/orders/cancel"] ?? 0),
      byInstrument: sortedRecord(byInstrument),
      bySide: sortedRecord(bySide),
      buyQuantity,
      sellQuantity,
      grossSubmittedQuantity,
      grossSubmittedNotional: Number(grossSubmittedNotional.toFixed(6)),
    },
    pnl: {
      realized: null,
      unrealized: null,
      total: null,
      currency: "USD",
      available: false,
      reason: "execution attribution and deterministic mark prices are a follow-up scoring slice",
    },
    fees: {
      total: 0,
      maker: 0,
      taker: 0,
      currency: "USD",
      policy: "fees-v0-zero-placeholder",
    },
    inventory: {
      netQuantityByInstrument: {},
      grossNotional: null,
      markPriceSource: "pending final-mid/last/reference mark",
    },
    marketQuality: {
      available: false,
      reason: "quote uptime, spread contribution, depth, and impact attribution are follow-up scoring slices",
    },
  };
}

function buildReport({ botResults, enforcementEvents, sessionReports, healthSamples, venueReadback, startedAt, completedAt, elapsedMs }) {
  const tickResults = sessionReports.flatMap((session) => session.ticks);
  const commandStatusSummary = summarizeCommandStatuses(tickResults);
  const totals = tickResults.reduce(
    (acc, tick) => {
      acc.ticks += 1;
      acc.failedTicks += tick.ok ? 0 : 1;
      acc.actions += tick.actions?.length ?? 0;
      acc.venueCommands += tick.venueCommands?.length ?? 0;
      acc.submittedCommands += tick.submission?.submitted ?? 0;
      acc.completedCommands += tick.submission?.completed ?? 0;
      acc.failedCommands += tick.submission?.failed ?? 0;
      acc.rejectedCommands += tick.submission?.rejected ?? 0;
      acc.timedOutCommands += tick.submission?.timedOut ?? 0;
      acc.dataCalls += Number(tick.dataCalls ?? 0);
      return acc;
    },
    { ticks: 0, failedTicks: 0, actions: 0, venueCommands: 0, submittedCommands: 0, completedCommands: 0, failedCommands: 0, rejectedCommands: 0, timedOutCommands: 0, dataCalls: 0 },
  );
  const healthSummary = summarizeHealth(healthSamples, totals);
  const status = reportStatus(enforcementEvents, healthSummary);
  return {
    schemaVersion: "reef.arena.localTickRun.v0",
    generatedAt: new Date().toISOString(),
    startedAt,
    completedAt,
    runId: config.runId,
    mode: {
      modeId: mode.modeId,
      version: mode.version,
      seed: mode.seed,
      scoringPolicyVersion: mode.scoringPolicyVersion,
      riskPolicyVersion: mode.riskPolicyVersion,
    },
    runPlan,
    expectations: {
      freezeBots: config.expectFreezeBots,
    },
    runnerProfile: { compartment: config.compartment, workerCount: 1, submitMode: config.submitMode },
    commandWaitMode: config.commandWaitMode,
    scoringAssumptions: scoringAssumptions(),
    status,
    elapsedMs,
    totals,
    commandAccounting: {
      draftCommands: totals.venueCommands,
      submittedCommands: totals.submittedCommands,
      terminalCommands: totals.completedCommands + totals.failedCommands,
      accountingGap: config.submitMode === "live"
        ? totals.venueCommands - totals.submittedCommands
        : 0,
    },
    commandStatusSummary,
    latencySummary: summarizeReportLatency(tickResults),
    activityBySchedulingClass: summarizeActivityBySchedulingClass(sessionReports),
    healthSummary,
    healthSamples,
    venueReadback,
    enforcementEvents,
    botResults,
    leaderboard: rankBotResults(
      botResults.filter((result) => result.scoreEligible && result.publicLeaderboard && !result.disqualified),
    ),
    diagnosticLeaderboard: rankBotResults(botResults.filter((result) => result.scoreEligible)),
    sessionReports,
  };
}

function reportForOutput(report) {
  if (config.reportShape === "full") {
    return report;
  }
  return compactArenaReport(report);
}

function compactArenaReport(report) {
  return {
    schemaVersion: report.schemaVersion,
    reportShape: "compact",
    generatedAt: report.generatedAt,
    startedAt: report.startedAt,
    completedAt: report.completedAt,
    runId: report.runId,
    mode: report.mode,
    runPlan: report.runPlan,
    expectations: report.expectations,
    runnerProfile: report.runnerProfile,
    commandWaitMode: report.commandWaitMode,
    scoringAssumptions: report.scoringAssumptions,
    status: report.status,
    elapsedMs: report.elapsedMs,
    totals: report.totals,
    commandAccounting: report.commandAccounting,
    commandStatusSummary: report.commandStatusSummary,
    latencySummary: report.latencySummary,
    activityBySchedulingClass: report.activityBySchedulingClass,
    healthSummary: report.healthSummary,
    venueReadback: compactVenueReadback(report.venueReadback),
    enforcementEvents: report.enforcementEvents,
    botResults: report.botResults,
    leaderboard: report.leaderboard,
    diagnosticLeaderboard: report.diagnosticLeaderboard,
    persistence: compactPersistence(report.persistence),
    omitted: {
      healthSamples: Array.isArray(report.healthSamples) ? report.healthSamples.length : 0,
      sessionReports: Array.isArray(report.sessionReports) ? report.sessionReports.length : 0,
      reason: "compact report shape omits high-volume per-tick detail",
    },
  };
}

function compactVenueReadback(venueReadback) {
  if (venueReadback === undefined) return undefined;
  return {
    mode: venueReadback.mode,
    skipped: venueReadback.skipped,
    projectionDrained: venueReadback.projectionDrained,
    projectionDrainRequired: venueReadback.projectionDrainRequired,
    availability: venueReadback.availability,
    snapshots: venueReadback.snapshots,
    ownOrders: Array.isArray(venueReadback.ownOrders)
      ? venueReadback.ownOrders.map((entry) => ({
        botId: entry.botId,
        participantId: entry.participantId,
        currentStatusCode: entry.current?.statusCode,
        currentOrderCount: entry.current?.body?.orders?.length ?? 0,
        historyStatusCode: entry.history?.statusCode,
        historyOrderCount: entry.history?.body?.orders?.length ?? 0,
      }))
      : [],
  };
}

function compactPersistence(persistence) {
  if (persistence === undefined) return undefined;
  return {
    enabled: persistence.enabled,
    skipped: persistence.skipped,
    operationCount: Array.isArray(persistence.operations) ? persistence.operations.length : 0,
    rawResultsStatusCode: persistence.rawResults?.statusCode,
    rawEnforcementEventsStatusCode: persistence.rawEnforcementEvents?.statusCode,
    leaderboardStatusCode: persistence.leaderboard?.statusCode,
    leaderboardEntry: persistence.leaderboardEntry,
  };
}

function summarizeReportLatency(tickResults) {
  const tickElapsedMs = tickResults
    .map((tick) => Number(tick.elapsedMs ?? 0))
    .filter((value) => Number.isFinite(value))
    .sort((left, right) => left - right);
  return {
    tickElapsedMs: {
      count: tickElapsedMs.length,
      p50: percentile(tickElapsedMs, 0.5),
      p95: percentile(tickElapsedMs, 0.95),
      p99: percentile(tickElapsedMs, 0.99),
      max: tickElapsedMs.length === 0 ? null : tickElapsedMs[tickElapsedMs.length - 1],
    },
  };
}

function reportStatus(enforcementEvents, healthSummary) {
  if (enforcementEvents.some((event) => event.decision === "freeze")) {
    return "completed_with_freezes";
  }
  if (healthSummary.status !== "pass") {
    return "completed_with_warnings";
  }
  return "completed";
}

function summarizeCommandStatuses(tickResults) {
  const commands = tickResults.flatMap((tick) => tick.submission?.commands ?? []);
  const byRoute = {};
  const byFinalStatus = {};
  const byFirstStatus = {};
  const rejectedByCode = {};
  const rejectedByBotId = {};
  let intakeElapsedMsTotal = 0;
  let statusElapsedMsTotal = 0;
  for (const command of commands) {
    increment(byRoute, command.route || "unknown");
    increment(byFinalStatus, command.finalStatus || "unknown");
    increment(byFirstStatus, command.firstStatus || "unknown");
    if (command.finalStatus === "REJECTED" || command.rejected === true) {
      const payload = safeJson(command.statusBody?.responsePayloadJson);
      const code = payload?.rejected?.code ?? command.statusBody?.resultStatus ?? "unknown";
      increment(rejectedByCode, code);
      increment(rejectedByBotId, botIdFromCommandId(command.commandId));
    }
    intakeElapsedMsTotal += Number(command.intakeElapsedMs ?? 0);
    statusElapsedMsTotal += Number(command.statusElapsedMs ?? 0);
  }
  return {
    commandCount: commands.length,
    timedOut: commands.filter((command) => command.timedOut).length,
    byRoute,
    byFinalStatus,
    byFirstStatus,
    rejectedByCode,
    rejectedByBotId,
    avgIntakeElapsedMs: commands.length === 0 ? 0 : intakeElapsedMsTotal / commands.length,
    avgStatusElapsedMs: commands.length === 0 ? 0 : statusElapsedMsTotal / commands.length,
  };
}

function summarizeActivityBySchedulingClass(sessionReports) {
  const summary = {};
  for (const session of sessionReports) {
    const key = session.schedulingClass ?? botSchedulingClass(session.bot);
    const counters = sessionCounters(session);
    const bucket = summary[key] ?? {
      botCount: 0,
      ticks: 0,
      actions: 0,
      venueCommands: 0,
      submittedCommands: 0,
      completedCommands: 0,
      failedCommands: 0,
      rejectedCommands: 0,
      timedOutCommands: 0,
      operationalPauses: 0,
      dataCalls: 0,
    };
    bucket.botCount += 1;
    bucket.ticks += counters.ticks;
    bucket.actions += counters.actions;
    bucket.venueCommands += counters.venueCommands;
    bucket.submittedCommands += counters.submittedCommands;
    bucket.completedCommands += counters.completedCommands;
    bucket.failedCommands += counters.failedCommands;
    bucket.rejectedCommands += counters.rejectedCommands;
    bucket.timedOutCommands += counters.timedOutCommands;
    bucket.operationalPauses += session.ticks.filter((tick) => tick.operationalControl !== undefined).length;
    bucket.dataCalls += counters.dataCalls;
    summary[key] = bucket;
  }
  return summary;
}

function increment(counter, key) {
  counter[key] = Number(counter[key] ?? 0) + 1;
}

function sortedRecord(record) {
  return Object.fromEntries(Object.entries(record).sort(([left], [right]) => left.localeCompare(right)));
}

function botIdFromCommandId(commandId) {
  const value = String(commandId ?? "");
  const prefix = `${mode.modeId}-`;
  const suffixIndex = value.lastIndexOf("-cmd-");
  if (!value.startsWith(prefix) || suffixIndex <= prefix.length) {
    return "unknown";
  }
  return value.slice(prefix.length, suffixIndex);
}

function rankBotResults(results) {
  return results
    .slice()
    .sort((left, right) => Number(left.disqualified) - Number(right.disqualified) || right.score - left.score || left.botId.localeCompare(right.botId))
    .map((result, index) => ({ rank: index + 1, ...result }));
}

function assertExpectedFreezeBots(report) {
  for (const botId of config.expectFreezeBots) {
    const events = report.enforcementEvents.filter((event) => event.botId === botId && event.decision === "freeze");
    if (events.length === 0) {
      throw new Error(`expected freeze event for bot ${botId}`);
    }
    const result = report.botResults.find((candidate) => candidate.botId === botId);
    if (result === undefined || !result.disqualified || result.freezeCount < 1) {
      throw new Error(`expected disqualified result for frozen bot ${botId}: ${JSON.stringify(result)}`);
    }
  }
}

async function maybeCollectHealthSample(healthSamples, tickIndex, tickReports) {
  if (tickIndex % runPlan.healthSampleEveryTicks !== 0) {
    return;
  }
  const fallbackOccurredAt = new Date(Date.parse("2026-07-04T14:30:00.000Z") + tickIndex * runPlan.tickIntervalMs).toISOString();
  const representativeTick = tickReports.find((tickReport) => tickReport.tick !== undefined)?.tick
    ?? tickReports.find((tickReport) => typeof tickReport.occurredAt === "string")
    ?? { occurredAt: fallbackOccurredAt };
  const snapshots = config.submitMode === "live"
    ? await liveHealthSnapshots()
    : dryRunHealthSnapshots(representativeTick);
  healthSamples.push({
    sampleIndex: healthSamples.length,
    tickIndex,
    botId: "",
    sampleScope: "arena_tick",
    occurredAt: representativeTick.occurredAt,
    postWarmup: tickIndex >= runPlan.warmupTicks,
    submittedCommands: tickReports.reduce((total, tickReport) => total + Number(tickReport.submission?.submitted ?? 0), 0),
    completedCommands: tickReports.reduce((total, tickReport) => total + Number(tickReport.submission?.completed ?? 0), 0),
    snapshots,
  });
}

async function liveHealthSnapshots() {
  const baseUrl = config.venueUrl.replace(/\/$/, "");
  const snapshots = [];
  for (const instrumentId of runPlan.healthInstruments) {
    const response = await getJson(`${baseUrl}/api/v1/market-data/snapshots/${encodeURIComponent(instrumentId)}`, readbackHeaders());
    snapshots.push(normalizeSnapshot(instrumentId, response.body?.snapshot ?? response.body, response.statusCode));
  }
  return snapshots;
}

function dryRunHealthSnapshots(tick) {
  const snapshots = [];
  const marketSnapshots = tick.marketSnapshots ?? {};
  for (const instrumentId of runPlan.healthInstruments) {
    snapshots.push(normalizeSnapshot(instrumentId, marketSnapshots[instrumentId] ?? {}, 200));
  }
  return snapshots;
}

function normalizeSnapshot(instrumentId, rawSnapshot, statusCode) {
  const bid = nullableNumber(rawSnapshot.bestBidPrice ?? rawSnapshot.bidPrice);
  const ask = nullableNumber(rawSnapshot.bestAskPrice ?? rawSnapshot.askPrice);
  const mid = nullableNumber(rawSnapshot.midPrice) ?? midpoint(bid, ask);
  const topOfBook = bid !== null && ask !== null && bid > 0 && ask > 0;
  const spread = topOfBook ? ask - bid : null;
  return {
    instrumentId,
    statusCode,
    bidPrice: bid,
    askPrice: ask,
    midPrice: mid,
    topOfBook,
    emptyBook: !topOfBook,
    lockedBook: topOfBook && bid === ask,
    crossedBook: topOfBook && bid > ask,
    quotedSpread: spread,
    quotedSpreadBps: spread !== null && mid !== null && mid > 0 ? (spread / mid) * 10000 : null,
    bidDepthAvailable: topOfBook,
    askDepthAvailable: topOfBook,
  };
}

function summarizeHealth(healthSamples, totals) {
  const postWarmupSamples = healthSamples.filter((sample) => sample.postWarmup);
  const samples = postWarmupSamples.length > 0 ? postWarmupSamples : healthSamples;
  const instrumentSamples = samples.flatMap((sample) => sample.snapshots ?? []);
  const spreadBps = instrumentSamples
    .map((snapshot) => snapshot.quotedSpreadBps)
    .filter((value) => value !== null && Number.isFinite(value))
    .sort((left, right) => left - right);
  const topOfBookCount = instrumentSamples.filter((snapshot) => snapshot.topOfBook).length;
  const depthCount = instrumentSamples.filter((snapshot) => snapshot.bidDepthAvailable && snapshot.askDepthAvailable).length;
  const sampleCount = instrumentSamples.length;
  const minTopOfBookPct = Number(mode.healthTargets?.minTopOfBookPct ?? 90);
  const minDepthPct = Number(mode.healthTargets?.minDepthPct ?? 90);
  const maxMedianQuotedSpreadBps = Number(mode.healthTargets?.maxMedianQuotedSpreadBps ?? 25);
  const maxP95QuotedSpreadBps = Number(mode.healthTargets?.maxP95QuotedSpreadBps ?? 50);
  const failures = [];
  const topOfBookPct = pct(topOfBookCount, sampleCount);
  const depthPct = pct(depthCount, sampleCount);
  const medianQuotedSpreadBps = percentile(spreadBps, 0.5);
  const p95QuotedSpreadBps = percentile(spreadBps, 0.95);
  const crossedBookCount = instrumentSamples.filter((snapshot) => snapshot.crossedBook).length;
  const emptyBookCount = instrumentSamples.filter((snapshot) => snapshot.emptyBook).length;
  const ticksWithVenueCommandsPct = pct(
    healthSamples.filter((sample) => sample.submittedCommands > 0 || config.submitMode === "dry-run").length,
    healthSamples.length,
  );

  if (sampleCount === 0) failures.push("no_health_samples");
  if (topOfBookPct < minTopOfBookPct) failures.push(`topOfBookPct ${topOfBookPct.toFixed(2)} < ${minTopOfBookPct}`);
  if (depthPct < minDepthPct) failures.push(`depthPct ${depthPct.toFixed(2)} < ${minDepthPct}`);
  if (medianQuotedSpreadBps !== null && medianQuotedSpreadBps > maxMedianQuotedSpreadBps) {
    failures.push(`medianQuotedSpreadBps ${medianQuotedSpreadBps.toFixed(2)} > ${maxMedianQuotedSpreadBps}`);
  }
  if (p95QuotedSpreadBps !== null && p95QuotedSpreadBps > maxP95QuotedSpreadBps) {
    failures.push(`p95QuotedSpreadBps ${p95QuotedSpreadBps.toFixed(2)} > ${maxP95QuotedSpreadBps}`);
  }
  if (crossedBookCount > 0) failures.push(`crossedBookCount ${crossedBookCount} > 0`);
  if (totals.failedTicks > Number(mode.healthTargets?.maxFailedTicks ?? 0)) failures.push(`failedTicks ${totals.failedTicks} > ${Number(mode.healthTargets?.maxFailedTicks ?? 0)}`);

  return {
    status: failures.length === 0 ? "pass" : "warn",
    failures,
    sampleCount,
    postWarmupSampleCount: postWarmupSamples.length,
    instrumentSampleCount: sampleCount,
    topOfBookPct,
    depthPct,
    medianQuotedSpreadBps,
    p95QuotedSpreadBps,
    crossedBookCount,
    lockedBookCount: instrumentSamples.filter((snapshot) => snapshot.lockedBook).length,
    emptyBookCount,
    ticksWithVenueCommandsPct,
    thresholds: {
      minTopOfBookPct,
      minDepthPct,
      maxMedianQuotedSpreadBps,
      maxP95QuotedSpreadBps,
    },
  };
}

async function collectVenueReadback(botResults) {
  if (config.submitMode !== "live") {
    return { mode: config.submitMode, skipped: true };
  }
  const baseUrl = config.venueUrl.replace(/\/$/, "");
  const availability = await waitForDataAvailability(baseUrl);
  const snapshots = [];
  for (const instrumentId of mode.instruments ?? ["AAPL"]) {
    snapshots.push({
      instrumentId,
      ...(await getJson(`${baseUrl}/api/v1/market-data/snapshots/${encodeURIComponent(instrumentId)}`, readbackHeaders())),
    });
  }
  const ownOrders = [];
  for (const result of botResults) {
    const participantId = participantIdForBot(result);
    ownOrders.push({
      botId: result.botId,
      participantId,
      current: await getJson(`${baseUrl}/api/v1/orders/current?participantId=${encodeURIComponent(participantId)}&limit=50`, readbackHeaders(participantId)),
      history: await getJson(`${baseUrl}/api/v1/orders/history?participantId=${encodeURIComponent(participantId)}&limit=50`, readbackHeaders(participantId)),
    });
  }
  return {
    mode: config.submitMode,
    skipped: false,
    availability,
    projectionDrained: availability.body?.projections === undefined
      ? false
      : availability.body.projections.every((projection) => Number(projection.lag ?? 0) === 0),
    projectionDrainRequired: config.requireProjectionDrain,
    snapshots,
    ownOrders,
  };
}

async function waitForDataAvailability(baseUrl) {
  const url = `${baseUrl}/api/v1/data/availability`;
  const deadline = Date.now() + config.projectionDrainTimeoutMs;
  let latest = await getJson(url, readbackHeaders());
  while (config.projectionDrainTimeoutMs > 0 && Date.now() <= deadline && !availabilityDrained(latest)) {
    await sleep(config.projectionDrainPollMs);
    latest = await getJson(url, readbackHeaders());
  }
  if (config.requireProjectionDrain && !availabilityDrained(latest)) {
    throw new Error(`projection drain requirement failed: ${JSON.stringify(latest.body)}`);
  }
  return latest;
}

function availabilityDrained(availability) {
  const projections = availability.body?.projections;
  return Array.isArray(projections) && projections.every((projection) => Number(projection.lag ?? 0) === 0);
}

function readbackHeaders(participantId = "") {
  return {
    "X-Client-Id": "arena-local-readback",
    ...(participantId ? { "X-Participant-Id": participantId } : {}),
  };
}

async function persistArenaResults(report) {
  if (!config.persistResults) {
    return { enabled: false, skipped: true };
  }
  const baseUrl = config.arenaAdminUrl.replace(/\/$/, "");
  const correlationId = `${config.runId}-persist`;
  const botVersions = selectedBots.map((bot) => ({ botId: bot.botId, versionId: bot.versionId }));
  const operations = [];
  for (const bot of selectedBots) {
    operations.push(await ensureArenaBot(baseUrl, bot, correlationId));
    operations.push(await ensureArenaBotVersion(baseUrl, bot, correlationId));
  }
  operations.push(await postArenaOk(baseUrl, "/internal/admin/arena/runs", {
    runId: config.runId,
    modeId: mode.modeId,
    scenarioId: mode.scenarioId,
    seed: Number(mode.seed ?? 0),
    policyVersion: mode.riskPolicyVersion ?? "arena-risk-v0",
    botVersions,
    actorId: config.actorId,
    correlationId,
  }, { allowAlreadyExists: true }));
  operations.push(await postArenaOk(baseUrl, "/internal/admin/arena/runs/status", {
    runId: config.runId,
    status: "running",
    actorId: config.actorId,
    correlationId,
  }, { allowInvalidTransition: true }));
  operations.push(await postArenaOk(baseUrl, "/internal/admin/arena/runs/status", {
    runId: config.runId,
    status: report.status === "completed" || report.status === "completed_with_freezes" || report.status === "completed_with_warnings" ? "completed" : "failed",
    actorId: config.actorId,
    correlationId,
  }, { allowInvalidTransition: true }));

  for (const result of report.botResults) {
    operations.push(await postArenaOk(baseUrl, "/internal/admin/arena/run-bot-results", {
      runId: config.runId,
      botId: result.botId,
      versionId: result.versionId,
      scoringPolicyVersion: mode.scoringPolicyVersion,
      finalEquity: result.score,
      realizedPnl: result.score - 1_000_000,
      maxDrawdown: result.disqualified ? 250_000 : 0,
      actionsProposed: result.actionsProposed,
      orderActionsProposed: result.venueCommands,
      dataCalls: result.dataCalls ?? 0,
      signalsGenerated: 0,
      disqualified: result.disqualified,
      scoreEligible: result.scoreEligible,
      publicLeaderboard: result.publicLeaderboard,
      actorId: config.actorId,
      correlationId,
    }, { allowAlreadyExists: true }));
  }

  for (const event of report.enforcementEvents) {
    operations.push(await postArenaOk(baseUrl, "/internal/admin/arena/run-enforcement-events", {
      runId: config.runId,
      botId: event.botId,
      versionId: event.versionId,
      decision: event.decision,
      reasonCode: event.reasonCode,
      reason: event.reason,
      policyVersion: event.policyVersion,
      countersJson: JSON.stringify(event.counters ?? {}),
      actorId: config.actorId,
      correlationId,
    }, { allowAlreadyExists: true }));
    if (event.decision === "freeze") {
      operations.push(await postArenaOk(baseUrl, "/internal/admin/arena/bot-versions/transition", {
        botId: event.botId,
        versionId: event.versionId,
        status: "quarantine",
        reason: `arena freeze ${config.runId}: ${event.reasonCode}`,
        actorId: config.actorId,
        correlationId,
      }, { allowInvalidTransition: true }));
    }
  }

  const rawResults = await getArenaJson(baseUrl, `/internal/admin/arena/run-bot-results?runId=${encodeURIComponent(config.runId)}&actorId=${encodeURIComponent(config.actorId)}`);
  const rawEnforcementEvents = await getArenaJson(baseUrl, `/internal/admin/arena/run-enforcement-events?runId=${encodeURIComponent(config.runId)}&actorId=${encodeURIComponent(config.actorId)}`);
  const leaderboard = await getArenaJson(
    baseUrl,
    `/internal/admin/arena/leaderboard?modeId=${encodeURIComponent(mode.modeId)}&scoringPolicyVersion=${encodeURIComponent(mode.scoringPolicyVersion)}&limit=50&actorId=${encodeURIComponent(config.actorId)}`,
  );
  const leaderboardEntry = leaderboard.body?.entries?.find((entry) => entry.runId === config.runId);
  if (rawResults.statusCode < 200 || rawResults.statusCode >= 300) {
    throw new Error(`arena run-bot-results readback failed (${rawResults.statusCode}): ${JSON.stringify(rawResults.body)}`);
  }
  if (rawEnforcementEvents.statusCode < 200 || rawEnforcementEvents.statusCode >= 300) {
    throw new Error(`arena run-enforcement-events readback failed (${rawEnforcementEvents.statusCode}): ${JSON.stringify(rawEnforcementEvents.body)}`);
  }
  if (leaderboard.statusCode < 200 || leaderboard.statusCode >= 300) {
    throw new Error(`arena leaderboard readback failed (${leaderboard.statusCode}): ${JSON.stringify(leaderboard.body)}`);
  }
  const expectsLeaderboardEntry = report.botResults.some((result) => result.scoreEligible && result.publicLeaderboard && !result.disqualified);
  if (leaderboardEntry === undefined && expectsLeaderboardEntry) {
    throw new Error(`arena leaderboard missing run ${config.runId}: ${JSON.stringify(leaderboard.body)}`);
  }
  return {
    enabled: true,
    skipped: false,
    operations,
    rawResults,
    rawEnforcementEvents,
    leaderboard,
    leaderboardEntry,
  };
}

async function ensureArenaBot(baseUrl, bot, correlationId) {
  return await postArenaOk(baseUrl, "/internal/admin/arena/bots", {
    botId: bot.botId,
    fileName: `${bot.entryPath}#${bot.botId}`,
    name: displayNameForBot(bot),
    publisher: "Reef Built-In",
    email: "arena-local@reef.local",
    description: `${bot.role ?? "bot"} ${bot.botId} for local arena mode ${mode.modeId}`,
    version: bot.catalogVersionId ?? bot.versionId,
    actorId: config.actorId,
    correlationId,
  }, { allowAlreadyExists: true });
}

async function ensureArenaBotVersion(baseUrl, bot, correlationId) {
  const version = await postArenaOk(baseUrl, "/internal/admin/arena/bot-versions", {
    botId: bot.botId,
    versionId: bot.versionId,
    sourceHash: `sha256:${bot.runnerKey}-source`,
    artifactHash: `sha256:${bot.runnerKey}-artifact`,
    sdkVersion: "1.5.0",
    apiVersion: "v1",
    dependencyManifestHash: `sha256:${bot.runnerKey}-deps`,
    actorId: config.actorId,
    correlationId,
  }, { allowAlreadyExists: true });
  for (const [status, reason] of [
    ["submitted", "local arena persistence submitted"],
    ["checks-passed", "local arena persistence checks passed"],
    ["approved", "local arena persistence approved"],
  ]) {
    await postArenaOk(baseUrl, "/internal/admin/arena/bot-versions/transition", {
      botId: bot.botId,
      versionId: bot.versionId,
      status,
      reason,
      actorId: config.actorId,
      correlationId,
    }, { allowInvalidTransition: true });
  }
  return version;
}

async function postArenaOk(baseUrl, path, payload, options = {}) {
  const response = await postJson(`${baseUrl}${path}`, payload, adminHeaders(payload.correlationId));
  const body = safeJson(response.body);
  if (response.statusCode >= 200 && response.statusCode < 300) {
    return { path, statusCode: response.statusCode, ok: true };
  }
  const text = JSON.stringify(body);
  if (options.allowAlreadyExists && text.includes("already exists")) {
    return { path, statusCode: response.statusCode, ok: true, ignored: "already_exists" };
  }
  if (options.allowInvalidTransition && (text.includes("invalid bot version transition") || text.includes("invalid arena run transition"))) {
    return { path, statusCode: response.statusCode, ok: true, ignored: "invalid_transition" };
  }
  throw new Error(`arena admin POST ${path} failed (${response.statusCode}): ${response.body}`);
}

async function getArenaJson(baseUrl, path) {
  return await getJson(`${baseUrl}${path}`, adminHeaders(`${config.runId}-persist`));
}

function adminHeaders(correlationId) {
  return {
    "X-Reef-Internal-Route": "true",
    "X-Reef-Actor-Id": config.actorId,
    "X-Correlation-Id": correlationId,
  };
}

async function submitVenueCommands(commands) {
  if (config.submitMode === "dry-run" || commands.length === 0) {
    return emptySubmission(config.submitMode);
  }

  const results = await Promise.all(commands.map(async (command) => {
    const submittedAt = performance.now();
    const intake = await postJson(`${config.venueUrl.replace(/\/$/, "")}${command.route}`, command.body, command.headers);
    const intakeElapsedMs = performance.now() - submittedAt;
    const intakeBody = safeJson(intake.body);
    const status = intake.statusCode >= 200 && intake.statusCode < 300
      ? await terminalStatusForAcceptedCommand(command, intakeBody)
      : { timedOut: false, statusCode: intake.statusCode, body: safeJson(intake.body) };
    const finalStatus = String(status.body?.status ?? "");
    const responseStatus = Number(status.body?.responseStatus ?? 0);
    return {
      commandId: command.body.commandId,
      route: command.route,
      intakeStatus: intake.statusCode,
      intakeBody,
      intakeElapsedMs,
      finalStatus,
      responseStatus,
      rejected: responseStatus >= 400 || String(status.body?.resultStatus ?? "").toLowerCase() === "rejected",
      timedOut: status.timedOut,
      statusWaitMode: config.commandWaitMode,
      statusPollCount: status.pollCount ?? 0,
      firstStatus: status.firstStatus ?? "",
      firstStatusElapsedMs: status.firstStatusElapsedMs ?? null,
      statusElapsedMs: status.elapsedMs ?? 0,
      elapsedMs: performance.now() - submittedAt,
      statusBody: status.body,
    };
  }));
  return summarizeSubmissionResults(results);
}

function emptySubmission(reason) {
  return {
    mode: config.submitMode,
    skippedReason: reason,
    submitted: 0,
    completed: 0,
    failed: 0,
    rejected: 0,
    timedOut: 0,
    commands: [],
  };
}

function summarizeSubmissionResults(results) {
  return {
    mode: config.submitMode,
    submitted: results.length,
    completed: results.filter((result) => result.finalStatus === "COMPLETED").length,
    failed: results.filter((result) => result.finalStatus === "FAILED" || result.intakeStatus < 200 || result.intakeStatus >= 300).length,
    rejected: results.filter((result) => result.rejected).length,
    timedOut: results.filter((result) => result.timedOut).length,
    commands: results,
  };
}

async function terminalStatusForAcceptedCommand(command, intakeBody) {
  if (config.commandWaitMode === "none") {
    return intakeStatus(command, intakeBody, "intake_response");
  }
  if (isTerminalIntakeBody(intakeBody)) {
    return terminalIntakeStatus(command, intakeBody);
  }
  if (typeof intakeBody.statusUrl !== "string" || intakeBody.statusUrl.length === 0) {
    return intakeStatus(command, intakeBody, "intake_response_without_status_url");
  }
  return await waitForCommandStatus(command);
}

function isTerminalIntakeBody(intakeBody) {
  return intakeBody !== null
    && typeof intakeBody === "object"
    && (intakeBody.accepted !== undefined || intakeBody.rejected !== undefined);
}

function terminalIntakeStatus(command, intakeBody) {
  const rejected = intakeBody.rejected !== undefined;
  const status = rejected ? "REJECTED" : "COMPLETED";
  const responseStatus = rejected ? 400 : 200;
  return {
    timedOut: false,
    statusCode: responseStatus,
    pollCount: 0,
    elapsedMs: 0,
    firstStatus: status,
    firstStatusElapsedMs: 0,
    body: {
      commandId: command.body.commandId,
      status,
      responseStatus,
      responsePayloadJson: JSON.stringify(intakeBody),
      resultStatus: rejected ? "rejected" : "accepted",
      source: "sync_result_intake_response",
    },
  };
}

function intakeStatus(command, intakeBody, source) {
  return {
    timedOut: false,
    statusCode: 200,
    pollCount: 0,
    elapsedMs: 0,
    firstStatus: String(intakeBody.status ?? "ACCEPTED"),
    firstStatusElapsedMs: 0,
    body: {
      commandId: command.body.commandId,
      status: String(intakeBody.status ?? "ACCEPTED"),
      responseStatus: 202,
      responsePayloadJson: JSON.stringify(intakeBody),
      source,
    },
  };
}

async function waitForCommandStatus(command) {
  const deadline = Date.now() + config.commandTimeoutMs;
  let last = { statusCode: 0, body: {} };
  const startedAt = performance.now();
  let pollCount = 0;
  let firstStatus = "";
  let firstStatusElapsedMs = null;
  while (Date.now() <= deadline) {
    const response = await getJson(
      `${config.venueUrl.replace(/\/$/, "")}/api/v1/commands/${encodeURIComponent(command.body.commandId)}`,
      statusReadHeaders(command),
    );
    pollCount += 1;
    last = response;
    const status = String(response.body?.status ?? "");
    if (firstStatus === "") {
      firstStatus = status;
      firstStatusElapsedMs = performance.now() - startedAt;
    }
    if (config.commandWaitMode === "accepted" && ["ACCEPTED", "EVENT_PUBLISHED", "COMPLETED", "FAILED", "REJECTED"].includes(status)) {
      return { timedOut: false, statusCode: response.statusCode, body: response.body, pollCount, firstStatus, firstStatusElapsedMs, elapsedMs: performance.now() - startedAt };
    }
    if (status === "COMPLETED" || status === "FAILED" || status === "REJECTED") {
      return { timedOut: false, statusCode: response.statusCode, body: response.body, pollCount, firstStatus, firstStatusElapsedMs, elapsedMs: performance.now() - startedAt };
    }
    await sleep(config.commandPollMs);
  }
  return { timedOut: true, statusCode: last.statusCode, body: last.body, pollCount, firstStatus, firstStatusElapsedMs, elapsedMs: performance.now() - startedAt };
}

async function seedReferenceData(bots) {
  const internal = { "X-Reef-Internal-Route": "true" };
  for (const instrumentId of mode.instruments ?? ["AAPL"]) {
    await postSetupOk("/reference/instruments", {
      instrumentId,
      symbol: instrumentId,
      assetClass: "US_EQ",
      currency: "USD",
    }, internal);
  }
  for (const bot of bots) {
    const identityKey = venueIdentityKey(bot);
    await postSetupOk("/reference/participants", {
      participantId: participantIdForIdentity(identityKey),
      name: `Arena ${bot.botId}`,
    }, internal);
    await postSetupOk("/reference/accounts", {
      accountId: accountIdForIdentity(identityKey),
      participantId: participantIdForIdentity(identityKey),
      accountType: bot.riskProfile.assetLedgerMode === "bypass" ? "HOUSE" : "CUSTOMER",
    }, internal);
    await postSetupOk("/auth/roles", {
      roleId: "order_trader",
      permissions: "order.submit,order.cancel,order.modify",
    }, internal);
    await postSetupOk("/auth/actor-roles", {
      actorId: actorIdForIdentity(identityKey),
      roleId: "order_trader",
    }, internal);
  }
}

async function postSetupOk(path, payload, headers) {
  const response = await postJson(`${config.venueUrl.replace(/\/$/, "")}${path}`, payload, headers);
  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new Error(`reference setup POST ${path} failed (${response.statusCode}): ${response.body}`);
  }
  return response;
}

function statusReadHeaders(command) {
  const participantId = command.headers["X-Participant-Id"] ?? command.body.participantId ?? "";
  return {
    "X-Client-Id": command.headers["X-Client-Id"] ?? "",
    "Idempotency-Key": command.headers["Idempotency-Key"] ?? "",
    ...(typeof participantId === "string" && participantId.length > 0
      ? { "X-Participant-Id": participantId }
      : {}),
  };
}

function sessionCounters(session) {
  const latencies = session.ticks.map((tick) => Number(tick.elapsedMs ?? 0)).sort((a, b) => a - b);
  return session.ticks.reduce(
    (acc, tick) => {
      acc.ticks += 1;
      acc.failedTicks += tick.ok ? 0 : 1;
      acc.actions += tick.actions?.length ?? 0;
      acc.venueCommands += tick.venueCommands?.length ?? 0;
      acc.submittedCommands += tick.submission?.submitted ?? 0;
      acc.completedCommands += tick.submission?.completed ?? 0;
      acc.failedCommands += tick.submission?.failed ?? 0;
      acc.rejectedCommands += tick.submission?.rejected ?? 0;
      acc.timedOutCommands += tick.submission?.timedOut ?? 0;
      acc.dataCalls += Number(tick.dataCalls ?? 0);
      acc.maxActionsPerTick = Math.max(acc.maxActionsPerTick, tick.actions?.length ?? 0);
      return acc;
    },
    { ticks: 0, failedTicks: 0, actions: 0, venueCommands: 0, submittedCommands: 0, completedCommands: 0, failedCommands: 0, rejectedCommands: 0, timedOutCommands: 0, dataCalls: 0, maxActionsPerTick: 0, latencyP95Ms: percentile(latencies, 0.95) },
  );
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
        const response = JSON.parse(line);
        const pending = this.pending.get(response.id);
        if (pending !== undefined) {
          this.pending.delete(response.id);
          pending.resolve(response);
        }
      }
      newlineIndex = this.buffer.indexOf("\n");
    }
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
    ["scripts/dev/bot-sdk-build-hosted-artifact.mjs", entryPath, `--out=${artifactPath}`, `--manifest-out=${manifestPath}`],
    { cwd: repoRoot, encoding: "utf8", env: { ...process.env, BOT_SDK_BUILD_TMP_ROOT: tmpdir() } },
  );
  if (build.status !== 0) {
    throw new Error(`artifact build failed for ${entryPath}\nstdout:\n${build.stdout}\nstderr:\n${build.stderr}`);
  }
  return {
    source: readFileSync(artifactPath, "utf8"),
    fileName: basename(artifactPath),
    manifest: readJson(manifestPath),
  };
}

function fixtureForBot(bot, schedule = {}) {
  const identityKey = venueIdentityKey(bot);
  const tickIntervalMs = positiveNumber(schedule.tickIntervalMs, runPlan.tickIntervalMs);
  const tickCount = Math.max(1, Number(schedule.tickCount ?? runPlan.tickCount));
  const fixture = {
    ...baseFixture,
    scenarioId: mode.scenarioId,
    runId: config.runId,
    venueSessionId: mode.venueSessionId,
    botId: bot.botId,
    botVersion: bot.versionId,
    actorId: actorIdForIdentity(identityKey),
    participantId: participantIdForIdentity(identityKey),
    accountId: accountIdForIdentity(identityKey),
    correlationId: `${mode.modeId}-${identityKey}`,
    config: {
      ...(baseFixture.config ?? {}),
      ...bot.runtimeConfigPreflight.values,
      ...(botSchedulingClass(bot) === "house_responsive" ? { houseLiquidity: houseLiquidityConfig(bot) } : {}),
    },
  };
  const scheduledFixture = {
    ...fixture,
    policy: {
      ...(fixture.policy ?? {}),
      tickIntervalMs,
    },
    ticks: scheduledTicks(fixture.ticks, { tickCount, tickIntervalMs }),
  };
  return withModeMarketData(scheduledFixture);
}

function buildRunPlan(modeConfig, runtimeConfig, fixture, botsOrCount) {
  const tickIntervalMs = positiveNumber(
    runtimeConfig.tickIntervalMs,
    positiveNumber(modeConfig.tickIntervalMs, positiveNumber(fixture.policy?.tickIntervalMs, 500)),
  );
  const durationSeconds = positiveNumber(runtimeConfig.durationSeconds, positiveNumber(modeConfig.durationSeconds, 0));
  const selectedBotsForPlan = Array.isArray(botsOrCount) ? botsOrCount : [];
  const selectedBotCount = selectedBotsForPlan.length > 0 ? selectedBotsForPlan.length : Math.max(1, Number(botsOrCount ?? 1));
  const tickCount = durationSeconds > 0
    ? Math.max(1, Math.ceil((durationSeconds * 1000) / tickIntervalMs))
    : Math.max(1, Number(modeConfig.ticks ?? fixture.ticks?.length ?? 1));
  const warmupSeconds = Math.max(0, positiveNumber(runtimeConfig.warmupSeconds, positiveNumber(modeConfig.warmupSeconds, 0)));
  const healthSampleIntervalMs = positiveNumber(runtimeConfig.healthSampleIntervalMs, positiveNumber(modeConfig.healthSampleIntervalMs, tickIntervalMs));
  const defaultHouseWakeIntervalMs = positiveNumber(modeConfig.houseLiquidityDefaults?.wakeIntervalMs, 250);
  const durationMs = tickCount * tickIntervalMs;
  const plannedTotalTickCount = selectedBotsForPlan.length === 0
    ? tickCount * selectedBotCount
    : selectedBotsForPlan.reduce((total, bot) => {
      if (botSchedulingClass(bot) !== "house_responsive") {
        return total + tickCount;
      }
      const intervalMs = positiveNumber(
        {
          ...(modeConfig.houseLiquidityDefaults ?? {}),
          ...(bot.houseLiquidity ?? {}),
        }.wakeIntervalMs,
        defaultHouseWakeIntervalMs,
      );
      return total + Math.max(1, Math.ceil(durationMs / intervalMs));
    }, 0);
  return {
    durationSeconds: Number(((tickCount * tickIntervalMs) / 1000).toFixed(3)),
    durationMs,
    configuredDurationSeconds: durationSeconds,
    tickIntervalMs,
    houseWakeIntervalMs: defaultHouseWakeIntervalMs,
    tickCount,
    perBotTickCount: tickCount,
    selectedBotCount,
    totalTickCount: plannedTotalTickCount,
    schedulingMode: "shared-arena-time",
    perBotDurationSeconds: Number(((tickCount * tickIntervalMs) / 1000).toFixed(3)),
    warmupSeconds,
    warmupTicks: Math.min(tickCount, Math.floor((warmupSeconds * 1000) / tickIntervalMs)),
    healthSampleIntervalMs,
    healthSampleEveryTicks: Math.max(1, Math.ceil(healthSampleIntervalMs / tickIntervalMs)),
    healthInstruments: nonEmptyArray(modeConfig.healthTargets?.primaryInstruments, [modeConfig.instruments?.[0] ?? "AAPL"]),
  };
}

function scheduledTicks(sourceTicks, plan) {
  const ticks = Array.isArray(sourceTicks) && sourceTicks.length > 0 ? sourceTicks : [{}];
  const baseTime = Date.parse(ticks[0].occurredAt ?? "2026-07-04T14:30:00.000Z");
  return Array.from({ length: plan.tickCount }, (_, index) => {
    const template = ticks[index % ticks.length];
    const occurredAt = new Date(baseTime + index * plan.tickIntervalMs).toISOString();
    return {
      ...template,
      occurredAt,
      marketSnapshots: Object.fromEntries(
        Object.entries(template.marketSnapshots ?? {}).map(([instrumentId, snapshot]) => [
          instrumentId,
          { ...snapshot, asOf: occurredAt },
        ]),
      ),
    };
  });
}

function botIdentityKey(bot) {
  return String(bot.botId ?? bot.runnerKey).replace(/[^A-Za-z0-9_.-]/g, "-");
}

function venueIdentityKey(bot) {
  return `${config.runId}-${botIdentityKey(bot)}`.replace(/[^A-Za-z0-9_.-]/g, "-");
}

function actorIdForIdentity(identityKey) {
  return `actor-${identityKey}`;
}

function participantIdForIdentity(identityKey) {
  return `participant-${identityKey}`;
}

function accountIdForIdentity(identityKey) {
  return `account-${identityKey}`;
}

function participantIdForBot(bot) {
  return participantIdForIdentity(venueIdentityKey(bot));
}

async function resolveRuntimeConfigForBot(bot) {
  const descriptors = runtimeConfigDescriptorsForBot(bot);
  if (descriptors.length === 0) {
    return {
      values: Object.freeze({}),
      report: {
        provider: "OpenBao",
        descriptorCount: 0,
        resolvedKeys: [],
      },
    };
  }
  const provider = runtimeConfigSecretProviderForBot(bot);
  const resolved = await resolveBotRuntimeConfig(descriptors, provider);
  return {
    values: resolved.values,
    report: runtimeConfigPreflightReport(provider, descriptors, resolved.values),
  };
}

function runtimeConfigDescriptorsForBot(bot) {
  const runtimeConfig = bot.runtimeConfig;
  if (runtimeConfig === undefined) return [];
  const values = runtimeConfig.values ?? {};
  return Object.keys(values).sort().map((key) => ({
    key,
    provider: "OpenBao",
    secretPath: runtimeConfig.secretPath,
    required: true,
    valueType: typeof values[key],
    description: `local arena ${bot.botId} ${key}`,
  }));
}

function localOpenBaoSecretProvider(bot) {
  return {
    provider: "OpenBao",
    async readSecret(path) {
      if (path !== bot.runtimeConfig?.secretPath) {
        return undefined;
      }
      return Object.freeze({ ...(bot.runtimeConfig.values ?? {}) });
    },
  };
}

function runtimeConfigSecretProviderForBot(bot) {
  if (config.openBaoAddr.length > 0 && config.openBaoToken.length > 0) {
    return createOpenBaoRuntimeSecretProvider({
      baoAddr: config.openBaoAddr,
      token: config.openBaoToken,
    });
  }
  return localOpenBaoSecretProvider(bot);
}

async function loadRuntimeConfigResolver() {
  if (process.versions.bun !== undefined) {
    const runtimeConfigModuleUrl = new URL("../../packages/bot-sdk/src/runtime-config.ts", import.meta.url);
    const { resolveBotRuntimeConfigV1 } = await import(runtimeConfigModuleUrl.href);
    return resolveBotRuntimeConfigV1;
  }
  return resolveBotRuntimeConfigLocalV1;
}

async function resolveBotRuntimeConfigLocalV1(descriptors, secretProvider) {
  const values = {};
  const seenKeys = new Set();

  for (const descriptor of descriptors) {
    validateRuntimeConfigDescriptor(descriptor, secretProvider);
    if (seenKeys.has(descriptor.key)) {
      throw new Error(`Duplicate bot runtime config key ${descriptor.key}.`);
    }
    seenKeys.add(descriptor.key);

    const secret = await secretProvider.readSecret(descriptor.secretPath);
    const value = runtimeConfigValueFromSecret(descriptor, secret);
    if (value === undefined) {
      if (descriptor.required) {
        throw new Error(`Missing required bot runtime config ${descriptor.key}.`);
      }
      continue;
    }
    values[descriptor.key] = assertRuntimeConfigValueType(descriptor, value);
  }

  return { values: Object.freeze({ ...values }) };
}

function validateRuntimeConfigDescriptor(descriptor, secretProvider) {
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(descriptor.key)) {
    throw new Error(`Invalid bot runtime config key ${descriptor.key}.`);
  }
  if (descriptor.provider !== secretProvider.provider) {
    throw new Error(`Runtime config provider mismatch for ${descriptor.key}.`);
  }
  if (descriptor.provider !== "OpenBao") {
    throw new Error(`Unsupported bot runtime config provider ${descriptor.provider}.`);
  }
  if (descriptor.secretPath.trim() === "") {
    throw new Error(`Runtime config secretPath is required for ${descriptor.key}.`);
  }
}

function runtimeConfigValueFromSecret(descriptor, secret) {
  if (secret === undefined) return undefined;
  if (typeof secret === "string" || typeof secret === "number" || typeof secret === "boolean") {
    return secret;
  }
  return secret[descriptor.key];
}

function assertRuntimeConfigValueType(descriptor, value) {
  const expected = descriptor.valueType ?? typeof value;
  if (typeof value !== expected) {
    throw new Error(`Bot runtime config ${descriptor.key} must be ${expected}.`);
  }
  return value;
}

function withModeMarketData(fixture) {
  const instruments = nonEmptyArray(mode.instruments, ["AAPL"]);
  const seedSnapshots = {
    AAPL: { bidPrice: 99.9, askPrice: 100.1, midPrice: 100, lastPrice: 100 },
    MSFT: { bidPrice: 199.8, askPrice: 200.2, midPrice: 200, lastPrice: 200 },
    NVDA: { bidPrice: 499.5, askPrice: 500.5, midPrice: 500, lastPrice: 500 },
    TSLA: { bidPrice: 249.75, askPrice: 250.25, midPrice: 250, lastPrice: 250 },
    AMZN: { bidPrice: 149.85, askPrice: 150.15, midPrice: 150, lastPrice: 150 },
  };
  return {
    ...fixture,
    historicalBars: {
      ...fixture.historicalBars,
      ...Object.fromEntries(instruments.map((instrumentId) => [instrumentId, bars(instrumentId, historicalCloses(instrumentId))])),
    },
    ticks: fixture.ticks.map((tick) => ({
      ...tick,
      marketSnapshots: {
        ...Object.fromEntries(instruments.map((instrumentId) => {
          const snapshot = seedSnapshots[instrumentId] ?? tick.marketSnapshots?.[instrumentId] ?? syntheticSnapshot(instrumentId);
          return [instrumentId, { instrumentId, ...snapshot, asOf: tick.occurredAt }];
        })),
      },
    })),
  };
}

function syntheticSnapshot(instrumentId) {
  const midPrice = 100 + (instrumentId.charCodeAt(0) % 50);
  return {
    bidPrice: Number((midPrice - 0.1).toFixed(2)),
    askPrice: Number((midPrice + 0.1).toFixed(2)),
    midPrice,
    lastPrice: midPrice,
  };
}

function historicalCloses(instrumentId) {
  const base = syntheticSnapshot(instrumentId).midPrice;
  if (instrumentId === "AAPL") return [100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 80, 85, 90, 92, 95];
  if (instrumentId === "MSFT") return [200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 220, 215, 212, 210, 205];
  return Array.from({ length: 20 }, (_, index) => Number((base + Math.sin(index / 2) * 2).toFixed(2)));
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

function percentile(sortedValues, pct) {
  if (sortedValues.length === 0) return 0;
  const index = Math.min(sortedValues.length - 1, Math.ceil(sortedValues.length * pct) - 1);
  return sortedValues[index];
}

function nullableNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function midpoint(bid, ask) {
  if (bid === null || ask === null) return null;
  return (bid + ask) / 2;
}

function pct(count, total) {
  return total > 0 ? (count / total) * 100 : 0;
}

function positiveNumber(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function nonEmptyArray(value, fallback) {
  return Array.isArray(value) && value.length > 0 ? value : fallback;
}

async function postJson(url, payload, headers = {}) {
  const response = await request("POST", url, payload, headers, 5000);
  return { ...response, body: response.body };
}

async function getJson(url, headers = {}) {
  const response = await request("GET", url, undefined, headers, 5000);
  return { statusCode: response.statusCode, body: safeJson(response.body) };
}

function request(method, url, payload, headers = {}, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const body = payload === undefined ? "" : JSON.stringify(payload);
    const transport = parsed.protocol === "https:" ? https : http;
    const req = transport.request(parsed, {
      method,
      timeout: timeoutMs,
      headers: {
        ...(payload === undefined ? {} : {
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
        }),
        ...headers,
      },
    }, (res) => {
      let data = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => {
        data += chunk;
      });
      res.on("end", () => {
        resolve({ statusCode: res.statusCode ?? 0, body: data });
      });
    });
    req.on("timeout", () => {
      req.destroy(new Error(`request timeout after ${timeoutMs}ms`));
    });
    req.on("error", reject);
    if (body.length > 0) {
      req.write(body);
    }
    req.end();
  });
}

function safeJson(raw) {
  if (typeof raw !== "string" || raw.length === 0) {
    return {};
  }
  try {
    return JSON.parse(raw);
  } catch {
    return { raw };
  }
}

function readJson(path) {
  return JSON.parse(readFileSync(resolve(repoRoot, path), "utf8"));
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
  const raw = optionValue(name);
  return raw === undefined ? fallback : raw;
}

function csvOption(name, fallback) {
  return stringOption(name, fallback).split(",").map((value) => value.trim()).filter(Boolean);
}

function localVersionId(entry) {
  return `${entry.versionId}-${config.runId}`.replace(/[^A-Za-z0-9_.-]/g, "-");
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}

await main();
