import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { env, loadDotEnv, sleep, waitForHttp } from "./lib/dev-utils.mjs";
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
  return { ...entry, catalogVersionId: entry.versionId, versionId: localVersionId(entry), riskProfile };
});
const baseFixture = readJson("packages/bot-sdk/fixtures/aapl-multi-tick.json");
const runPlan = buildRunPlan(mode, config, baseFixture, selectedBots.length);

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
        artifact: buildArtifact(bot.entryPath, bot.runnerKey),
        runtimeConfigPreflight: await resolveRuntimeConfigForBot(bot),
      });
    }
    for (const bot of bots) {
      const load = await worker.request({
        type: "loadBot",
        botKey: bot.runnerKey,
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

    const sessionReports = [];
    const enforcementEvents = [];
    const healthSamples = [];
    for (const [botIndex, bot] of bots.entries()) {
      console.log(`arena bot start ${botIndex + 1}/${bots.length}: ${bot.botId} ticks=${runPlan.tickCount}`);
      const result = await runBotSession(bot, healthSamples);
      sessionReports.push(result);
      enforcementEvents.push(...enforceBot(bot, result));
      console.log(`arena bot complete ${botIndex + 1}/${bots.length}: ${bot.botId} ticks=${result.ticks.length}`);
    }

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
    mkdirSync(dirname(config.out), { recursive: true });
    writeFileSync(config.out, `${JSON.stringify(report, null, 2)}\n`);
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

async function runBotSession(bot, healthSamples) {
  const fixture = fixtureForBot(bot);
  const sessionId = `${mode.modeId}-${bot.runnerKey}-${Date.now()}`;
  const start = await worker.request({ type: "startSession", botKey: bot.runnerKey, sessionId, fixture });
  if (!start.ok) {
    return { bot, sessionId, start, ticks: [], stop: undefined };
  }
  const ticks = [];
  const scheduled = fixture.ticks.slice(0, runPlan.tickCount);
  for (const [tickIndex, tick] of scheduled.entries()) {
    const tickResult = await worker.request({ type: "runTick", sessionId, tick });
    const policyViolation = tickPolicyViolation(bot, tickResult);
    if (policyViolation !== undefined) {
      ticks.push({
        ...tickResult,
        ok: false,
        policyViolation,
        issues: [...(tickResult.issues ?? []), policyViolation],
        venueCommands: [],
        submission: emptySubmission("policy_violation"),
      });
      break;
    }
    const tickReport = {
      ...tickResult,
      submission: await submitVenueCommands(tickResult.venueCommands ?? []),
    };
    ticks.push(tickReport);
    await maybeCollectHealthSample(healthSamples, bot, tick, tickIndex, tickReport);
    if (tickIndex === 0 || (tickIndex + 1) % 10 === 0 || tickIndex + 1 === scheduled.length) {
      console.log(`arena bot progress ${bot.botId}: tick=${tickIndex + 1}/${scheduled.length} commands=${tickReport.submission?.submitted ?? 0} timedOut=${tickReport.submission?.timedOut ?? 0}`);
    }
    if (config.paceTicks && tickIndex + 1 < scheduled.length) {
      await sleep(runPlan.tickIntervalMs);
    }
  }
  const stop = await worker.request({ type: "stopSession", sessionId });
  return { bot, sessionId, start, ticks, stop };
}

function tickPolicyViolation(bot, tickResult) {
  const actionCount = tickResult.actions?.length ?? 0;
  if (actionCount > bot.riskProfile.maxActionsPerTick) {
    return {
      code: "max_actions_per_tick",
      message: `actions ${actionCount} > ${bot.riskProfile.maxActionsPerTick}`,
      observed: actionCount,
      limit: bot.riskProfile.maxActionsPerTick,
    };
  }
  return undefined;
}

function enforceBot(bot, session) {
  const events = [];
  const counters = sessionCounters(session);
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
    const freezeCount = enforcementEvents.filter((event) => event.botId === session.bot.botId).length;
    const score = Math.max(
      0,
      1_000_000 + counters.venueCommands * 250 + counters.actions * 25 - counters.failedTicks * 100_000 - freezeCount * 250_000,
    );
    return {
      botId: session.bot.botId,
      versionId: session.bot.versionId,
      runnerKey: session.bot.runnerKey,
      role: session.bot.role,
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
      disqualified: freezeCount > 0,
      score,
    };
  });
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
    status: enforcementEvents.some((event) => event.decision === "freeze") ? "completed_with_freezes" : "completed",
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

function summarizeCommandStatuses(tickResults) {
  const commands = tickResults.flatMap((tick) => tick.submission?.commands ?? []);
  const byRoute = {};
  const byFinalStatus = {};
  const byFirstStatus = {};
  let intakeElapsedMsTotal = 0;
  let statusElapsedMsTotal = 0;
  for (const command of commands) {
    increment(byRoute, command.route || "unknown");
    increment(byFinalStatus, command.finalStatus || "unknown");
    increment(byFirstStatus, command.firstStatus || "unknown");
    intakeElapsedMsTotal += Number(command.intakeElapsedMs ?? 0);
    statusElapsedMsTotal += Number(command.statusElapsedMs ?? 0);
  }
  return {
    commandCount: commands.length,
    timedOut: commands.filter((command) => command.timedOut).length,
    byRoute,
    byFinalStatus,
    byFirstStatus,
    avgIntakeElapsedMs: commands.length === 0 ? 0 : intakeElapsedMsTotal / commands.length,
    avgStatusElapsedMs: commands.length === 0 ? 0 : statusElapsedMsTotal / commands.length,
  };
}

function increment(counter, key) {
  counter[key] = Number(counter[key] ?? 0) + 1;
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

async function maybeCollectHealthSample(healthSamples, bot, tick, tickIndex, tickReport) {
  if (tickIndex % runPlan.healthSampleEveryTicks !== 0) {
    return;
  }
  const snapshots = config.submitMode === "live"
    ? await liveHealthSnapshots()
    : dryRunHealthSnapshots(tick);
  healthSamples.push({
    sampleIndex: healthSamples.length,
    tickIndex,
    botId: bot.botId,
    occurredAt: tick.occurredAt,
    postWarmup: tickIndex >= runPlan.warmupTicks,
    submittedCommands: Number(tickReport.submission?.submitted ?? 0),
    completedCommands: Number(tickReport.submission?.completed ?? 0),
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
    const participantId = `participant-${result.runnerKey}`;
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
    status: report.status === "completed" || report.status === "completed_with_freezes" ? "completed" : "failed",
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
    fileName: bot.entryPath,
    name: bot.botId,
    publisher: "Reef Built-In",
    email: "arena-local@reef.local",
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
  if (typeof intakeBody.statusUrl === "string" && intakeBody.statusUrl.length > 0) {
    return await waitForCommandStatus(command);
  }
  return intakeStatus(command, intakeBody, "intake_response");
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
    if (config.commandWaitMode === "accepted" && ["ACCEPTED", "EVENT_PUBLISHED", "COMPLETED", "FAILED"].includes(status)) {
      return { timedOut: false, statusCode: response.statusCode, body: response.body, pollCount, firstStatus, firstStatusElapsedMs, elapsedMs: performance.now() - startedAt };
    }
    if (status === "COMPLETED" || status === "FAILED") {
      return { timedOut: false, statusCode: response.statusCode, body: response.body, pollCount, firstStatus, firstStatusElapsedMs, elapsedMs: performance.now() - startedAt };
    }
    await sleep(config.commandPollMs);
  }
  return { timedOut: true, statusCode: last.statusCode, body: last.body, pollCount, firstStatus, firstStatusElapsedMs, elapsedMs: performance.now() - startedAt };
}

async function seedReferenceData(bots) {
  const internal = { "X-Reef-Internal-Route": "true" };
  for (const instrumentId of mode.instruments ?? ["AAPL"]) {
    await postJson(`${config.venueUrl.replace(/\/$/, "")}/reference/instruments`, {
      instrumentId,
      symbol: instrumentId,
      assetClass: "US_EQ",
      currency: "USD",
    }, internal);
  }
  for (const bot of bots) {
    await postJson(`${config.venueUrl.replace(/\/$/, "")}/reference/participants`, {
      participantId: `participant-${bot.runnerKey}`,
      name: `Arena ${bot.botId}`,
    }, internal);
    await postJson(`${config.venueUrl.replace(/\/$/, "")}/reference/accounts`, {
      accountId: `account-${bot.runnerKey}`,
      participantId: `participant-${bot.runnerKey}`,
      accountType: bot.riskProfile.assetLedgerMode === "bypass" ? "HOUSE" : "CUSTOMER",
    }, internal);
    await postJson(`${config.venueUrl.replace(/\/$/, "")}/auth/roles`, {
      roleId: "order_trader",
      permissions: "order.submit,order.cancel,order.modify",
    }, internal);
    await postJson(`${config.venueUrl.replace(/\/$/, "")}/auth/actor-roles`, {
      actorId: `actor-${bot.runnerKey}`,
      roleId: "order_trader",
    }, internal);
  }
}

function statusReadHeaders(command) {
  return {
    "X-Client-Id": command.headers["X-Client-Id"] ?? "",
    "Idempotency-Key": command.headers["Idempotency-Key"] ?? "",
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
      acc.failedCommands += tick.submission?.failed ?? 0;
      acc.rejectedCommands += tick.submission?.rejected ?? 0;
      acc.timedOutCommands += tick.submission?.timedOut ?? 0;
      acc.dataCalls += Number(tick.dataCalls ?? 0);
      acc.maxActionsPerTick = Math.max(acc.maxActionsPerTick, tick.actions?.length ?? 0);
      return acc;
    },
    { ticks: 0, failedTicks: 0, actions: 0, venueCommands: 0, submittedCommands: 0, failedCommands: 0, rejectedCommands: 0, timedOutCommands: 0, dataCalls: 0, maxActionsPerTick: 0, latencyP95Ms: percentile(latencies, 0.95) },
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

function fixtureForBot(bot) {
  const fixture = {
    ...baseFixture,
    scenarioId: mode.scenarioId,
    runId: config.runId,
    venueSessionId: mode.venueSessionId,
    botId: bot.botId,
    botVersion: bot.versionId,
    actorId: `actor-${bot.runnerKey}`,
    participantId: `participant-${bot.runnerKey}`,
    accountId: `account-${bot.runnerKey}`,
    correlationId: `${mode.modeId}-${bot.runnerKey}`,
    config: {
      ...(baseFixture.config ?? {}),
      ...bot.runtimeConfigPreflight.values,
    },
  };
  const scheduledFixture = {
    ...fixture,
    policy: {
      ...(fixture.policy ?? {}),
      tickIntervalMs: runPlan.tickIntervalMs,
    },
    ticks: scheduledTicks(fixture.ticks, runPlan),
  };
  if (bot.runnerKey === "multi-symbol" || bot.runnerKey === "technical") {
    return withMultiSymbolData(scheduledFixture);
  }
  return scheduledFixture;
}

function buildRunPlan(modeConfig, runtimeConfig, fixture, botCount) {
  const tickIntervalMs = positiveNumber(
    runtimeConfig.tickIntervalMs,
    positiveNumber(modeConfig.tickIntervalMs, positiveNumber(fixture.policy?.tickIntervalMs, 500)),
  );
  const durationSeconds = positiveNumber(runtimeConfig.durationSeconds, positiveNumber(modeConfig.durationSeconds, 0));
  const selectedBotCount = Math.max(1, Number(botCount ?? 1));
  const totalTickCount = durationSeconds > 0
    ? Math.max(1, Math.ceil((durationSeconds * 1000) / tickIntervalMs))
    : Math.max(1, Number(modeConfig.ticks ?? fixture.ticks?.length ?? 1)) * selectedBotCount;
  const tickCount = durationSeconds > 0
    ? Math.max(1, Math.ceil(totalTickCount / selectedBotCount))
    : Math.max(1, Number(modeConfig.ticks ?? fixture.ticks?.length ?? 1));
  const plannedTotalTickCount = tickCount * selectedBotCount;
  const warmupSeconds = Math.max(0, positiveNumber(runtimeConfig.warmupSeconds, positiveNumber(modeConfig.warmupSeconds, 0)));
  const healthSampleIntervalMs = positiveNumber(runtimeConfig.healthSampleIntervalMs, positiveNumber(modeConfig.healthSampleIntervalMs, tickIntervalMs));
  return {
    durationSeconds: durationSeconds > 0 ? Number(((plannedTotalTickCount * tickIntervalMs) / 1000).toFixed(3)) : Number(((tickCount * tickIntervalMs) / 1000).toFixed(3)),
    configuredDurationSeconds: durationSeconds,
    tickIntervalMs,
    tickCount,
    perBotTickCount: tickCount,
    selectedBotCount,
    totalTickCount: plannedTotalTickCount,
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

function withMultiSymbolData(fixture) {
  return {
    ...fixture,
    historicalBars: {
      AAPL: bars("AAPL", [100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 80, 85, 90, 92, 95]),
      MSFT: bars("MSFT", [200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 200, 220, 215, 212, 210, 205]),
    },
    ticks: fixture.ticks.map((tick) => ({
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
