import { spawn, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync } from "node:fs";
import http from "node:http";
import https from "node:https";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { env, loadDotEnv, sleep, waitForHttp } from "./lib/dev-utils.mjs";
import {
  hostedBotContainerArgs,
  hostedBotContainerReachableUrl,
  hostedWorkerProcessEnv,
  validateHostedBotContainerNetwork,
} from "./lib/bot-isolation.mjs";
import { writeJsonFileStreaming } from "./lib/large-json-writer.mjs";
import { createOpenBaoRuntimeSecretProvider, runtimeConfigPreflightReport } from "./lib/openbao-runtime-config.mjs";
import {
  enrichBotResultsWithExecutionDiagnostics,
  marketPriceValue,
  priceFromExecutionPrice,
} from "./lib/arena-execution-diagnostics.mjs";
import {
  attachScoreBreakdowns,
  buildScoreContext,
  summarizeScoreCalibration,
} from "./lib/arena-score-breakdown.mjs";
import { reconcileArenaEconomics } from "./lib/arena-economic-reconciliation.mjs";
import {
  resolveActorProfile as resolvePolicyActorProfile,
  resolveActorProfileCatalog,
  canonicalHash,
  resolveEconomicPolicy,
  resolvePolicyComposition,
  resolveScoringPolicy,
} from "./lib/arena-policy-resolver.mjs";

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
  runnerIsolation: stringOption("--runner-isolation", "process"),
  runnerWorkerScope: stringOption("--runner-worker-scope", "shared"),
  runnerContainerNetwork: stringOption("--runner-container-network", ""),
  runnerMaxOutputBytes: numberOption("--runner-max-output-bytes", 1024 * 1024),
  runnerRequestTimeoutMs: numberOption("--runner-request-timeout-ms", 10000),
  submitMode: stringOption("--submit-mode", "dry-run"),
  readMode: stringOption("--read-mode", "fixture"),
  venueUrl: stringOption("--venue-url", env("BOT_SDK_VENUE_URL", env("RUNTIME_BASE_URL", ""))),
  arenaAdminUrl: stringOption("--arena-admin-url", env("ARENA_ADMIN_API_URL", env("BOT_SDK_VENUE_URL", env("RUNTIME_BASE_URL", "")))),
  adminApiToken: stringOption("--admin-api-token", env("ADMIN_API_TOKEN", "")),
  arenaAdminApiToken: stringOption("--arena-admin-api-token", env("ARENA_ADMIN_API_TOKEN", "")),
  openBaoAddr: stringOption("--openbao-addr", env("OPENBAO_ADDR", env("VAULT_ADDR", ""))),
  openBaoToken: stringOption("--openbao-token", env("OPENBAO_TOKEN", env("VAULT_TOKEN", ""))),
  seedReference: args.includes("--seed-reference"),
  persistResults: args.includes("--persist-results"),
  requireRosterBinding: args.includes("--require-roster-binding"),
  requireEconomicReconciliation: args.includes("--require-economic-reconciliation"),
  actorId: stringOption("--actor-id", env("ADMIN_ACTOR_ID", "admin-cli")),
  commandTimeoutMs: numberOption("--command-timeout-ms", 15000),
  commandPollMs: numberOption("--command-poll-ms", 250),
  commandWaitMode: stringOption("--command-wait-mode", "terminal"),
  projectionDrainTimeoutMs: numberOption("--projection-drain-timeout-ms", 0),
  projectionDrainPollMs: numberOption("--projection-drain-poll-ms", 500),
  projectionDrainCadence: stringOption("--projection-drain-cadence", "per-submission"),
  projectorPreflight: stringOption("--projector-preflight", env("ARENA_PROJECTOR_PREFLIGHT", "auto")),
  durationSeconds: numberOption("--duration-seconds", 0),
  tickIntervalMs: numberOption("--tick-interval-ms", 0),
  warmupSeconds: numberOption("--warmup-seconds", 0),
  healthSampleIntervalMs: numberOption("--health-sample-interval-ms", 0),
  scoringPolicyVersion: stringOption("--scoring-policy-version", ""),
  admissionWindowId: stringOption("--admission-window-id", env("ARENA_ADMISSION_WINDOW_ID", "")),
  rosterSnapshotId: stringOption("--roster-snapshot-id", env("ARENA_ROSTER_SNAPSHOT_ID", "")),
  rosterSnapshotHash: stringOption("--roster-snapshot-hash", env("ARENA_ROSTER_SNAPSHOT_HASH", "")),
  requireProjectionDrain: args.includes("--require-projection-drain"),
  paceTicks: args.includes("--pace-ticks"),
  out: stringOption("--out", "/tmp/reef-arena-local-tick-run.json"),
  reportShape: stringOption("--report-shape", "full"),
  skipProjectorPreflight: args.includes("--skip-projector-preflight"),
};

if (!["vm", "ses"].includes(config.compartment)) {
  throw new Error(`unsupported --compartment=${config.compartment}; expected vm or ses`);
}
if (!["process", "container"].includes(config.runnerIsolation)) {
  throw new Error(`unsupported --runner-isolation=${config.runnerIsolation}; expected process or container`);
}
if (!["shared", "per-bot"].includes(config.runnerWorkerScope)) {
  throw new Error(`unsupported --runner-worker-scope=${config.runnerWorkerScope}; expected shared or per-bot`);
}
const runnerContainerNetwork = config.runnerContainerNetwork.length > 0
  ? validateHostedBotContainerNetwork(config.runnerContainerNetwork)
  : config.runnerIsolation === "container" && config.submitMode === "live"
    ? "bridge"
    : "none";
if (!["dry-run", "live"].includes(config.submitMode)) {
  throw new Error(`unsupported --submit-mode=${config.submitMode}; expected dry-run or live`);
}
if (!["fixture", "live"].includes(config.readMode)) {
  throw new Error(`unsupported --read-mode=${config.readMode}; expected fixture or live`);
}
if (!["terminal", "accepted", "none"].includes(config.commandWaitMode)) {
  throw new Error(`unsupported --command-wait-mode=${config.commandWaitMode}; expected terminal, accepted, or none`);
}
if (!["per-submission", "scheduled-event", "final"].includes(config.projectionDrainCadence)) {
  throw new Error(`unsupported --projection-drain-cadence=${config.projectionDrainCadence}; expected per-submission, scheduled-event, or final`);
}
if (!["full", "compact"].includes(config.reportShape)) {
  throw new Error(`unsupported --report-shape=${config.reportShape}; expected full or compact`);
}
if (!["auto", "http", "docker", "skip"].includes(config.projectorPreflight)) {
  throw new Error(`unsupported --projector-preflight=${config.projectorPreflight}; expected auto, http, docker, or skip`);
}
if (config.submitMode === "live" && config.venueUrl.length === 0) {
  throw new Error("--venue-url or BOT_SDK_VENUE_URL is required when --submit-mode=live");
}
if (config.persistResults && config.arenaAdminUrl.length === 0) {
  throw new Error("--arena-admin-url, ARENA_ADMIN_API_URL, --venue-url, or BOT_SDK_VENUE_URL is required when --persist-results is set");
}
if ((config.persistResults || config.requireRosterBinding) && [config.admissionWindowId, config.rosterSnapshotId, config.rosterSnapshotHash].some((value) => value.length === 0)) {
  throw new Error("--admission-window-id, --roster-snapshot-id, and --roster-snapshot-hash are required for persisted or roster-bound runs");
}
if ((config.persistResults || config.requireRosterBinding) && !/^sha256:[a-f0-9]{64}$/.test(config.rosterSnapshotHash)) {
  throw new Error("--roster-snapshot-hash must be a canonical sha256 digest");
}

const mode = readJson(config.mode);
if (config.scoringPolicyVersion.length > 0) {
  if (!/^score-v\d+$/.test(config.scoringPolicyVersion)) {
    throw new Error(`unsupported --scoring-policy-version=${config.scoringPolicyVersion}`);
  }
  mode.scoringPolicyVersion = config.scoringPolicyVersion;
  mode.scoringPolicyPath = `packages/scenario-definitions/arena/scoring/${config.scoringPolicyVersion}.json`;
}
const catalog = readJson(mode.catalogPath);
const riskProfiles = catalog.riskProfiles ?? {};
const seedSetHash = canonicalHash([Number(mode.seed)]);
const riskPolicyHash = canonicalHash({
  schemaVersion: "reef.arena.riskPolicySet.v1",
  version: mode.riskPolicyVersion,
  profiles: riskProfiles,
});
const actorProfileCatalog = resolveActorProfileCatalog(readJson(mode.actorProfileCatalogPath));
const economicPolicy = resolveEconomicPolicy(readJson(mode.economicPolicyPath));
const scoringPolicy = resolveScoringPolicy(readJson(mode.scoringPolicyPath));
const policyComposition = resolvePolicyComposition(mode, actorProfileCatalog, economicPolicy, scoringPolicy);
const selectedBots = [...mode.botRefs, ...config.extraBots].map((botId) => {
  const entry = catalog.bots.find((bot) => bot.botId === botId);
  if (entry === undefined) {
    throw new Error(`mode references unknown bot ${botId}`);
  }
  const riskProfile = riskProfiles[entry.riskProfile];
  if (riskProfile === undefined) {
    throw new Error(`bot ${botId} references unknown risk profile ${entry.riskProfile}`);
  }
  const actorProfile = resolvePolicyActorProfile(
    { ...entry, actorClass: actorClassForBot(entry) },
    actorProfileCatalog,
    mode.actorProfileDefaults?.[entry.role],
  );
  return { ...entry, riskProfileName: entry.riskProfile, catalogVersionId: entry.versionId, versionId: localVersionId(entry), riskProfile, actorProfile };
});
const baseFixture = readJson("packages/bot-sdk/fixtures/aapl-multi-tick.json");
const runPlan = buildRunPlan(mode, config, baseFixture, selectedBots);

const outDir = mkdtempSync(join(tmpdir(), "reef-arena-local-tick-"));
const workers = [];
let sharedWorker;

async function main() {
  const startedAt = performance.now();
  const startedAtIso = new Date().toISOString();
  const phaseTimings = [];

  const runPhase = async (name, action) => {
    const phaseStartedAt = performance.now();
    console.log(`arena phase start: ${name}`);
    try {
      return await action();
    } finally {
      const elapsedMs = Math.round(performance.now() - phaseStartedAt);
      phaseTimings.push({ name, elapsedMs });
      console.log(`arena phase complete: ${name} elapsedMs=${elapsedMs}`);
    }
  };

  try {
    if (config.runnerWorkerScope === "shared") {
      sharedWorker = await runPhase("start-shared-worker", () => startRunnerWorker("arena-tick-worker-0"));
    }
    if (config.submitMode === "live") {
      await runPhase("prepare-live-venue", async () => {
        await waitForHttp(`${config.venueUrl.replace(/\/$/, "")}/health`, 120);
        await assertLiveMarketQualityProjectors();
        if (config.seedReference) {
          await seedReferenceData(selectedBots);
        }
      });
    }
    const bots = [];
    await runPhase("prepare-bots", async () => {
      for (const bot of selectedBots) {
        const botWorker = config.runnerWorkerScope === "per-bot"
          ? await startRunnerWorker(`arena-tick-worker-${safeWorkerId(bot.botId)}`)
          : sharedWorker;
        bots.push({
          ...bot,
          worker: botWorker,
          identityKey: botIdentityKey(bot),
          artifact: buildArtifact(bot.entryPath, bot.botId),
          runtimeConfigPreflight: await resolveRuntimeConfigForBot(bot),
        });
      }
      for (const bot of bots) {
        bot.loadResult = await loadBotInWorker(bot);
      }
    });

    const healthSamples = [];
    const arenaRun = await runPhase("run-scheduled-ticks", () => runArenaSessions(bots, healthSamples));
    const sessionReports = arenaRun.sessionReports;
    const enforcementEvents = sessionReports.flatMap((result) => enforceBot(result.bot, result));

    const baseBotResults = scoreBots(sessionReports, enforcementEvents);
    const venueReadback = await runPhase("collect-venue-readback", () => collectVenueReadback(baseBotResults));
    const botResults = await runPhase("score-results", () => attachScoreBreakdowns(enrichBotResultsWithExecutionDiagnostics(baseBotResults, venueReadback, healthSamples, {
      fallbackInstruments: mode.instruments ?? [],
    }), scoreContext()));
    const completedAtIso = new Date().toISOString();
    const report = await runPhase("build-report", () => buildReport({
      botResults,
      enforcementEvents,
      sessionReports,
      healthSamples,
      venueReadback,
      pacingSamples: arenaRun.pacingSamples,
      startedAt: startedAtIso,
      completedAt: completedAtIso,
      elapsedMs: performance.now() - startedAt,
      phaseTimings,
    }));
    report.persistence = await runPhase("persist-arena-results", () => persistArenaResults(report));
    await runPhase("write-report", () => writeJsonFileStreaming(config.out, reportForOutput(report), { space: 2 }));
    if (config.requireEconomicReconciliation && report.economicReconciliation.status !== "pass") {
      throw new Error(`arena economic reconciliation failed: ${report.economicReconciliation.reconciliationHash}`);
    }
    assertExpectedFreezeBots(report);
    console.log(`arena local tick run complete: ${resolve(config.out)}`);
    console.log(
      `status=${report.status} bots=${botResults.length} ticks=${report.totals.ticks} venueCommands=${report.totals.venueCommands} submitted=${report.totals.submittedCommands} freezes=${enforcementEvents.filter((event) => event.decision === "freeze").length}`,
    );
    for (const entry of report.leaderboard) {
      console.log(`rank=${entry.rank} bot=${entry.botId} score=${entry.score} disqualified=${entry.disqualified}`);
    }
  } finally {
    for (const runnerWorker of workers.reverse()) {
      await runnerWorker.shutdown();
    }
  }
}

async function loadBotInWorker(bot) {
  try {
    return await bot.worker.request(
      {
        type: "loadBot",
        botKey: bot.botId,
        source: bot.artifact.source,
        fileName: bot.artifact.fileName,
        executionLimits: {
          tickTimeoutMs: bot.riskProfile.maxTickLatencyMs,
          lifecycleTimeoutMs: bot.riskProfile.maxTickLatencyMs,
        },
      },
      config.runnerRequestTimeoutMs,
    );
  } catch (error) {
    return {
      ok: false,
      type: "loadBotResult",
      botKey: bot.botId,
      error: runnerIssue("runner_worker_load_failed", error),
    };
  }
}

async function startRunnerWorker(workerId) {
  const runnerWorker = new RunnerWorker(workerId, config.compartment, config.runnerIsolation);
  workers.push(runnerWorker);
  await runnerWorker.start();
  return runnerWorker;
}

function safeWorkerId(value) {
  return String(value).replace(/[^a-zA-Z0-9_.-]/g, "-").slice(0, 64);
}

function runnerIssue(code, error) {
  return {
    code,
    message: error instanceof Error ? error.message : String(error),
    runnerIsolation: config.runnerIsolation,
    runnerWorkerScope: config.runnerWorkerScope,
    ...(config.runnerIsolation === "container" ? { runnerContainerNetwork } : {}),
  };
}

function runnerIssueFromWorkerResult(fallbackCode, result) {
  const issue = result.error ?? result.issues?.[0];
  return {
    code: issue?.code ?? fallbackCode,
    message: issue?.message ?? JSON.stringify(result),
    runnerIsolation: config.runnerIsolation,
    runnerWorkerScope: config.runnerWorkerScope,
    ...(config.runnerIsolation === "container" ? { runnerContainerNetwork } : {}),
  };
}

async function runArenaSessions(bots, healthSamples) {
  const sessions = [];
  for (const [botIndex, bot] of bots.entries()) {
    const schedulingClass = botSchedulingClass(bot);
    const expectedTicks = ticksForSchedulingClass(schedulingClass, botScheduleIntervalMs(bot));
    console.log(`arena bot start ${botIndex + 1}/${bots.length}: ${bot.botId} class=${schedulingClass} ticks=${expectedTicks}`);
    sessions.push(await startBotSession(bot));
  }

  const pacingSamples = [];
  const schedulerStartedAt = performance.now();
  for (const event of schedulerEvents(sessions)) {
    const eventStartedAt = performance.now();
    const startLagMs = eventStartedAt - schedulerStartedAt - event.offsetMs;
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
    const projectionDrain = await drainProjectionsAfterScheduledEvent(tickReports.map((result) => result.tickReport));
    const eventCompletedAt = performance.now();
    let sleepMs = 0;
    if (config.paceTicks && event.nextOffsetMs !== null) {
      sleepMs = Math.max(0, schedulerStartedAt + event.nextOffsetMs - performance.now());
      if (sleepMs > 0) {
        await sleep(sleepMs);
      }
    }
    pacingSamples.push({
      offsetMs: event.offsetMs,
      nextOffsetMs: event.nextOffsetMs,
      scheduledSessionCount: event.sessions.length,
      executedTickCount: tickReports.length,
      sampleHealth: event.sampleHealth,
      projectionDrain,
      startLagMs,
      completionLagMs: eventCompletedAt - schedulerStartedAt - event.offsetMs,
      workElapsedMs: eventCompletedAt - eventStartedAt,
      sleepMs,
      wallElapsedMs: eventCompletedAt - schedulerStartedAt,
    });
  }

  await Promise.all(sessions.map(async (session) => {
    if (session.start.ok && !session.workerFailed) {
      try {
        session.stop = await session.worker.request(
          { type: "stopSession", sessionId: session.sessionId },
          config.runnerRequestTimeoutMs,
        );
      } catch (error) {
        session.stop = {
          ok: false,
          type: "stopSessionResult",
          sessionId: session.sessionId,
          botKey: session.bot.botId,
          error: runnerIssue("runner_worker_stop_failed", error),
        };
        session.ticks.push({
          tick: session.ticks.length,
          schedulingClass: session.schedulingClass,
          offsetMs: null,
          ok: false,
          issues: [session.stop.error],
          venueCommands: [],
          submission: emptySubmission(session.stop.error.code),
        });
      }
    } else if (session.start.ok && session.workerFailed) {
      session.stop = {
        ok: false,
        type: "stopSessionResult",
        sessionId: session.sessionId,
        botKey: session.bot.botId,
        error: runnerIssue("runner_worker_stop_skipped", "worker already failed"),
      };
    }
  }));
  for (const [index, session] of sessions.entries()) {
    console.log(`arena bot complete ${index + 1}/${sessions.length}: ${session.bot.botId} ticks=${session.ticks.length}`);
  }

  return {
    sessionReports: sessions.map(({ scheduled, stoppedForPolicy, ...session }) => session),
    pacingSamples,
  };
}

async function startBotSession(bot) {
  const schedulingClass = botSchedulingClass(bot);
  const intervalMs = botScheduleIntervalMs(bot);
  const tickCount = ticksForSchedulingClass(schedulingClass, intervalMs);
  const fixture = fixtureForBot(bot, { tickCount, tickIntervalMs: intervalMs });
  const sessionId = `${mode.modeId}-${bot.identityKey}-${Date.now()}`;
  if (bot.loadResult !== undefined && !bot.loadResult.ok) {
    return {
      bot,
      worker: bot.worker,
      sessionId,
      schedulingClass,
      intervalMs,
      start: {
        ok: false,
        type: "startSessionResult",
        botKey: bot.botId,
        sessionId,
        error: runnerIssueFromWorkerResult("runner_worker_load_failed", bot.loadResult),
      },
      scheduled: [],
      ticks: [],
      stop: undefined,
      stoppedForPolicy: true,
    };
  }
  let start;
  try {
    start = await bot.worker.request(
      {
        type: "startSession",
        botKey: bot.botId,
        sessionId,
        fixture,
        ...(config.submitMode === "live" && config.readMode === "live"
          ? {
              liveClientOptions: {
                baseUrl: workerVenueUrl(),
                participantId: participantIdForBot(bot),
              },
            }
          : {}),
      },
      config.runnerRequestTimeoutMs,
    );
  } catch (error) {
    start = {
      ok: false,
      type: "startSessionResult",
      botKey: bot.botId,
      sessionId,
      error: runnerIssue("runner_worker_start_failed", error),
    };
  }
  if (!start.ok) {
    return { bot, worker: bot.worker, sessionId, schedulingClass, intervalMs, start, scheduled: [], ticks: [], stop: undefined, stoppedForPolicy: true };
  }
  const scheduled = fixture.ticks.slice(0, tickCount);
  return { bot, worker: bot.worker, sessionId, schedulingClass, intervalMs, start, scheduled, ticks: [], stop: undefined, stoppedForPolicy: false };
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

function actorClassForBot(bot) {
  if (bot.role === "market-maker") return "house_market_maker";
  if (bot.role === "npc") return "npc_flow";
  if (bot.role === "benchmark") return "benchmark";
  return "competitor";
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
  let tickResult;
  try {
    tickResult = await session.worker.request(
      { type: "runTick", sessionId: session.sessionId, tick },
      config.runnerRequestTimeoutMs,
    );
  } catch (error) {
    session.stoppedForPolicy = true;
    session.workerFailed = true;
    const issue = runnerIssue("runner_worker_tick_failed", error);
    return {
      tick,
      schedulingClass: session.schedulingClass,
      offsetMs,
      ok: false,
      issues: [issue],
      venueCommands: [],
      submission: emptySubmission(issue.code),
    };
  }
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
  const submission = await submitVenueCommands(tickResult.venueCommands ?? []);
  const tickReport = {
    ...tickResult,
    tick,
    schedulingClass: session.schedulingClass,
    offsetMs,
    submission: await drainProjectionsAfterSubmission(submission),
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
  if (config.submitMode !== "live") {
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
  let replace;
  try {
    replace = await session.worker.request(
      { type: "replaceOrders", sessionId: session.sessionId, orders },
      config.runnerRequestTimeoutMs,
    );
  } catch (error) {
    return {
      ok: false,
      issue: runnerIssue("runner_worker_replace_orders_failed", error),
    };
  }
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
    reasons.push(`session_start_failed: ${session.start.error?.message ?? JSON.stringify(session.start.error ?? session.start)}`);
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
  const runnerFailure = sessionRunnerFailure(session);
  events.push({
    type: "arena.enforcement.v0",
    runId: config.runId,
    botId: bot.botId,
    versionId: bot.versionId,
    decision: "freeze",
    reasonCode: runnerFailure === undefined ? "tick_policy_violation" : "runner_isolation_failure",
    reason: reasons.join("; "),
    policyVersion: mode.riskPolicyVersion,
    counters,
    ...(runnerFailure === undefined ? {} : { runnerFailure }),
    occurredAt: new Date().toISOString(),
  });
  return events;
}

function sessionRunnerFailure(session) {
  const startIssue = session.start?.error;
  if (isRunnerIssue(startIssue)) {
    return startIssue;
  }
  for (const tick of session.ticks) {
    const issue = (tick.issues ?? []).find(isRunnerIssue);
    if (issue !== undefined) {
      return issue;
    }
  }
  return undefined;
}

function isRunnerIssue(issue) {
  return typeof issue?.code === "string" && issue.code.startsWith("runner_worker_");
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
      actorClass: actorClassForBot(session.bot),
      actorProfile: session.bot.actorProfile,
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
      conductMetrics: summarizeConductMetrics(session, counters, { freezeCount, operationalPauseCount }),
    };
  });
}

function displayNameForBot(bot) {
  return typeof bot.displayName === "string" && bot.displayName.trim().length > 0
    ? bot.displayName.trim()
    : bot.botId;
}

function scoringAssumptions() {
  const scoreV1Enabled = scoringPolicy.publicScoringEnabled;
  return {
    schemaVersion: "reef.arena.scoringAssumptions.v0",
    scoringPolicyVersion: mode.scoringPolicyVersion,
    npcDifficultyMode: "leaderboard-partition-plus-shadow-multiplier",
    npcDifficultyBuckets: npcDifficultyBuckets(selectedBots),
    economicPolicyLock: "run-scoped; final scoring must use the report policyEnvelopeHash",
    scoreBasis: scoreV1Enabled
      ? "public score uses score-v1 final-equity minus inventory-risk, command-quality, and enforcement penalties; shadowScore remains calibration-only"
      : "public score remains participation-and-policy-compliance; scoreBreakdown.shadowScore reports score-v0 tuning inputs",
    leaderboardScope: "score-eligible public competitor bots only",
    houseBots: "diagnostics-only; excluded from public leaderboard and not treated as bad actors when supplying configured liquidity",
    pnl: {
      status: scoreV1Enabled ? "ranked-input" : "diagnostic-not-ranked",
      basis: scoreV1Enabled
        ? "zero-fee cash plus marked inventory from participant-scoped fill readback is the headline public score input"
        : "zero-fee cash/inventory diagnostics from participant-scoped fill readback; public score remains participation/policy-compliance",
      markPriceSource: "final venue top-of-book mid, latest health sample mid, then deterministic fixture mid",
      fees: "zero placeholder until maker/taker fee policy is configured",
    },
    tradingMetrics: {
      status: "command-mix plus execution diagnostics v0",
      source: "runner pre-submit venue commands, command status summaries, and participant-scoped order-fill readback",
      fillsAndExecutions: scoreV1Enabled
        ? "score-v1 uses PnL/final equity and penalties; fill ratio, completion rate, and execution notional remain shadow calibration inputs"
        : "scoreBreakdown.shadowScore uses fill ratio, completion rate, and execution notional as report-only tuning inputs",
    },
  };
}

function scoreContext() {
  return buildScoreContext({
    scoringPolicyVersion: mode.scoringPolicyVersion,
    scoringPolicy,
    npcDifficultyBuckets: npcDifficultyBuckets(selectedBots),
  });
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
      reason: "venue fill readback unavailable before live execution diagnostics are attached",
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

function summarizeConductMetrics(session, counters, enforcement) {
  let maxVenueCommandsPerTick = 0;
  let submitCommands = 0;
  let modifyCommands = 0;
  let cancelCommands = 0;
  let noopActions = 0;

  for (const tick of session.ticks) {
    maxVenueCommandsPerTick = Math.max(maxVenueCommandsPerTick, tick.venueCommands?.length ?? 0);
    for (const action of tick.actions ?? []) {
      if (action.type === "noop") {
        noopActions += 1;
      }
    }
    for (const command of tick.venueCommands ?? []) {
      if (command.route === "/api/v1/orders/submit") submitCommands += 1;
      if (command.route === "/api/v1/orders/modify") modifyCommands += 1;
      if (command.route === "/api/v1/orders/cancel") cancelCommands += 1;
    }
  }

  const orderCommands = submitCommands + modifyCommands + cancelCommands;
  const cancelReplaceCommands = modifyCommands + cancelCommands;
  return {
    schemaVersion: "reef.arena.conductMetrics.v0",
    policyVersion: mode.scoringPolicyVersion,
    status: enforcement.freezeCount > 0 ? "disqualified" : "reported",
    orderCommands,
    submitCommands,
    modifyCommands,
    cancelCommands,
    noopActions,
    cancelReplaceRatio: ratio(cancelReplaceCommands, Math.max(1, submitCommands)),
    invalidIntentRate: ratio(counters.rejectedCommands + counters.failedCommands, Math.max(1, counters.submittedCommands)),
    timeoutRate: ratio(counters.timedOutCommands, Math.max(1, counters.submittedCommands)),
    maxActionsPerTick: counters.maxActionsPerTick,
    maxVenueCommandsPerTick,
    freezeCount: enforcement.freezeCount,
    operationalPauseCount: enforcement.operationalPauseCount,
    notes: "conduct inputs are scored only when enabled by the resolved scoring policy",
  };
}

function buildReport({ botResults, enforcementEvents, sessionReports, healthSamples, venueReadback, pacingSamples, startedAt, completedAt, elapsedMs, phaseTimings }) {
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
  const marketQualitySummary = summarizeMarketQuality(healthSamples);
  const botResultsWithLiquidity = attachLiquidityDiagnostics(botResults, marketQualitySummary, venueReadback);
  const economicReconciliation = reconcileArenaEconomics(botResultsWithLiquidity, economicPolicy);
  const liquiditySummary = summarizeLiquidityProviders(botResultsWithLiquidity, marketQualitySummary);
  const baseStatus = reportStatus(enforcementEvents, healthSummary);
  const status = config.requireEconomicReconciliation && economicReconciliation.status !== "pass"
    ? "failed_economic_reconciliation"
    : baseStatus;
  const envelope = policyEnvelope();
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
      scoringPolicyHash: scoringPolicy.contentHash,
      riskPolicyVersion: mode.riskPolicyVersion,
      riskPolicyHash,
      seedSetHash,
      economicPolicyVersion: mode.economicPolicyVersion,
      economicPolicyHash: economicPolicy.contentHash,
      liquidityPolicyVersion: mode.liquidityPolicyVersion,
      backgroundFlowPolicyVersion: mode.backgroundFlowPolicyVersion,
      creditPolicyVersion: mode.creditPolicyVersion,
      interventionPolicyVersion: mode.interventionPolicyVersion,
      actorProfileCatalogVersion: actorProfileCatalog.version,
      actorProfileCatalogHash: actorProfileCatalog.contentHash,
      policyCompositionHash: policyComposition.compositionHash,
      npcDifficultyBuckets: npcDifficultyBuckets(selectedBots),
    },
    policyEnvelope: envelope,
    policyEnvelopeHash: `sha256:${stableHash(envelope)}`,
    rosterBinding: {
      admissionWindowId: config.admissionWindowId,
      rosterSnapshotId: config.rosterSnapshotId,
      rosterSnapshotHash: config.rosterSnapshotHash,
    },
    resolvedPolicyArtifacts: {
      scoringPolicy: policyArtifact(scoringPolicy),
      economicPolicy: policyArtifact(economicPolicy),
    },
    runPlan,
    expectations: {
      freezeBots: config.expectFreezeBots,
    },
    runnerProfile: {
      compartment: config.compartment,
      isolation: config.runnerIsolation,
      workerScope: config.runnerWorkerScope,
      ...(config.runnerIsolation === "container" ? { containerNetwork: runnerContainerNetwork } : {}),
      workerCount: workers.length,
      submitMode: config.submitMode,
      ...(config.runnerIsolation === "container" && config.submitMode === "live" ? { workerVenueUrl: workerVenueUrl() } : {}),
    },
    commandWaitMode: config.commandWaitMode,
    projectionDrainCadence: config.projectionDrainCadence,
    scoringAssumptions: scoringAssumptions(),
    status,
    elapsedMs,
    phaseTimings,
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
    pacingSummary: summarizePacing(pacingSamples ?? [], elapsedMs),
    activityBySchedulingClass: summarizeActivityBySchedulingClass(sessionReports),
    healthSummary,
    marketQualitySummary,
    liquiditySummary,
    executionSummary: venueReadback?.executionSummary,
    scoringCalibration: summarizeScoreCalibration(botResultsWithLiquidity),
    economicReconciliation,
    healthSamples,
    venueReadback,
    enforcementEvents,
    botResults: botResultsWithLiquidity,
    leaderboard: rankBotResults(
      botResultsWithLiquidity.filter((result) => result.scoreEligible && result.publicLeaderboard && !result.disqualified),
    ),
    diagnosticLeaderboard: rankBotResults(botResultsWithLiquidity.filter((result) => result.scoreEligible)),
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
    policyEnvelope: report.policyEnvelope,
    policyEnvelopeHash: report.policyEnvelopeHash,
    rosterBinding: report.rosterBinding,
    resolvedPolicyArtifacts: report.resolvedPolicyArtifacts,
    runPlan: report.runPlan,
    expectations: report.expectations,
    runnerProfile: report.runnerProfile,
    commandWaitMode: report.commandWaitMode,
    projectionDrainCadence: report.projectionDrainCadence,
    scoringAssumptions: report.scoringAssumptions,
    status: report.status,
    elapsedMs: report.elapsedMs,
    phaseTimings: report.phaseTimings,
    totals: report.totals,
    commandAccounting: report.commandAccounting,
    commandStatusSummary: report.commandStatusSummary,
    latencySummary: report.latencySummary,
    pacingSummary: report.pacingSummary,
    activityBySchedulingClass: report.activityBySchedulingClass,
    healthSummary: report.healthSummary,
    marketQualitySummary: report.marketQualitySummary,
    liquiditySummary: report.liquiditySummary,
    executionSummary: report.executionSummary,
    scoringCalibration: report.scoringCalibration,
    economicReconciliation: report.economicReconciliation,
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

function policyEnvelope() {
  return {
    schemaVersion: "reef.arena.policyEnvelope.v1",
    modeId: mode.modeId,
    modeVersion: mode.version,
    scenarioId: mode.scenarioId,
    venueSessionId: mode.venueSessionId,
    seed: Number(mode.seed ?? 0),
    visibleDataPolicyVersion: mode.visibleDataPolicyVersion,
    actionPolicyVersion: mode.actionPolicyVersion,
    riskPolicyVersion: mode.riskPolicyVersion,
    riskPolicyHash,
    seedSetHash,
    scoringPolicyVersion: mode.scoringPolicyVersion,
    scoringPolicyHash: scoringPolicy.contentHash,
    economicPolicyVersion: mode.economicPolicyVersion,
    economicPolicyHash: economicPolicy.contentHash,
    liquidityPolicyVersion: mode.liquidityPolicyVersion,
    backgroundFlowPolicyVersion: mode.backgroundFlowPolicyVersion,
    creditPolicyVersion: mode.creditPolicyVersion,
    interventionPolicyVersion: mode.interventionPolicyVersion,
    actorProfileCatalog: {
      catalogId: actorProfileCatalog.catalogId,
      version: actorProfileCatalog.version,
      contentHash: actorProfileCatalog.contentHash,
    },
    economicPolicy: {
      policyId: economicPolicy.policyId,
      version: economicPolicy.version,
      contentHash: economicPolicy.contentHash,
    },
    scoringPolicy: {
      policyId: scoringPolicy.policyId,
      version: scoringPolicy.version,
      contentHash: scoringPolicy.contentHash,
    },
    policyCompositionHash: policyComposition.compositionHash,
    actorProfiles: summarizeActorProfiles(selectedBots).profiles.map((profile) => ({
      botId: profile.botId,
      actorClass: profile.actorClass,
      profileId: profile.profileId,
      profileVersion: profile.profileVersion,
      profileHash: profile.profileHash,
      difficultyBucket: profile.difficultyBucket,
      scoreEffect: profile.scoreEffect,
    })),
    npcDifficultyBuckets: npcDifficultyBuckets(selectedBots),
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
    executionSummary: venueReadback.executionSummary,
    ownOrders: Array.isArray(venueReadback.ownOrders)
      ? venueReadback.ownOrders.map((entry) => ({
        botId: entry.botId,
        participantId: entry.participantId,
        currentStatusCode: entry.current?.statusCode,
        currentOrderCount: entry.current?.body?.orders?.length ?? 0,
        historyStatusCode: entry.history?.statusCode,
        historyOrderCount: entry.history?.body?.orders?.length ?? 0,
        fillsStatusCode: entry.fills?.statusCode,
        fillCount: entry.fills?.body?.fills?.length ?? 0,
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

function summarizePacing(samples, elapsedMs) {
  const startLagMs = sortedFinite(samples.map((sample) => sample.startLagMs));
  const completionLagMs = sortedFinite(samples.map((sample) => sample.completionLagMs));
  const workElapsedMs = sortedFinite(samples.map((sample) => sample.workElapsedMs));
  const sleepMs = sortedFinite(samples.map((sample) => sample.sleepMs));
  const lastSample = samples[samples.length - 1];
  const finalOffsetMs = samples.reduce((max, sample) => Math.max(max, Number(sample.offsetMs ?? 0)), 0);
  const finalCompletionLagMs = lastSample === undefined ? 0 : Number(lastSample.completionLagMs ?? 0);
  return {
    schemaVersion: "reef.arena.pacingSummary.v0",
    enabled: config.paceTicks,
    scheduler: config.paceTicks ? "absolute-offset-from-run-start" : "unpaced",
    scheduledDurationMs: runPlan.durationMs,
    scheduledEventCount: samples.length,
    finalOffsetMs,
    elapsedMs,
    eventSpanWallMs: lastSample === undefined ? 0 : Number(lastSample.wallElapsedMs ?? 0),
    finalCompletionLagMs,
    maxStartLagMs: startLagMs.length === 0 ? 0 : startLagMs[startLagMs.length - 1],
    maxCompletionLagMs: completionLagMs.length === 0 ? 0 : completionLagMs[completionLagMs.length - 1],
    eventsBehindSchedule: samples.filter((sample) => Number(sample.startLagMs ?? 0) > 0).length,
    eventsCompletedBehindSchedule: samples.filter((sample) => Number(sample.completionLagMs ?? 0) > 0).length,
    totalSleepMs: sum(sleepMs),
    startLagMs: distributionFromSorted(startLagMs),
    completionLagMs: distributionFromSorted(completionLagMs),
    workElapsedMs: distributionFromSorted(workElapsedMs),
    sleepMs: distributionFromSorted(sleepMs),
  };
}

function sortedFinite(values) {
  return values
    .map((value) => Number(value))
    .filter((value) => Number.isFinite(value))
    .sort((left, right) => left - right);
}

function distributionFromSorted(sortedValues) {
  return {
    count: sortedValues.length,
    p50: percentile(sortedValues, 0.5),
    p95: percentile(sortedValues, 0.95),
    p99: percentile(sortedValues, 0.99),
    max: sortedValues.length === 0 ? 0 : sortedValues[sortedValues.length - 1],
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

async function maybeCollectHealthSample(healthSamples, healthSampleIndex, tickReports, offsetMs = healthSampleIndex * runPlan.healthSampleIntervalMs) {
  const fallbackOccurredAt = new Date(Date.parse("2026-07-04T14:30:00.000Z") + offsetMs).toISOString();
  const representativeTick = tickReports.find((tickReport) => tickReport.tick !== undefined)?.tick
    ?? tickReports.find((tickReport) => typeof tickReport.occurredAt === "string")
    ?? { occurredAt: fallbackOccurredAt };
  const snapshots = config.submitMode === "live"
    ? await liveHealthSnapshots()
    : dryRunHealthSnapshots(representativeTick);
  healthSamples.push({
    sampleIndex: healthSamples.length,
    tickIndex: healthSampleIndex,
    botId: "",
    sampleScope: "arena_tick",
    occurredAt: representativeTick.occurredAt,
    postWarmup: offsetMs >= runPlan.warmupSeconds * 1000,
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
  const bid = marketPriceValue(rawSnapshot.bestBidPrice ?? rawSnapshot.bidPrice);
  const ask = marketPriceValue(rawSnapshot.bestAskPrice ?? rawSnapshot.askPrice);
  const mid = marketPriceValue(rawSnapshot.midPrice) ?? midpoint(bid, ask);
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

function summarizeMarketQuality(healthSamples) {
  const postWarmupSamples = healthSamples.filter((sample) => sample.postWarmup);
  const samples = postWarmupSamples.length > 0 ? postWarmupSamples : healthSamples;
  const minTopOfBookPct = Number(mode.healthTargets?.minTopOfBookPct ?? 90);
  const minDepthPct = Number(mode.healthTargets?.minDepthPct ?? 90);
  const maxMedianQuotedSpreadBps = Number(mode.healthTargets?.maxMedianQuotedSpreadBps ?? 25);
  const maxP95QuotedSpreadBps = Number(mode.healthTargets?.maxP95QuotedSpreadBps ?? 50);
  const byInstrument = new Map();

  for (const sample of samples) {
    for (const snapshot of sample.snapshots ?? []) {
      const instrumentId = String(snapshot.instrumentId ?? "");
      if (instrumentId.length === 0) continue;
      const bucket = byInstrument.get(instrumentId) ?? {
        instrumentId,
        sampleCount: 0,
        topOfBookCount: 0,
        depthCount: 0,
        crossedBookCount: 0,
        lockedBookCount: 0,
        emptyBookCount: 0,
        spreadBps: [],
        firstCrossedAt: null,
        firstEmptyAt: null,
      };
      bucket.sampleCount += 1;
      if (snapshot.topOfBook) bucket.topOfBookCount += 1;
      if (snapshot.bidDepthAvailable && snapshot.askDepthAvailable) bucket.depthCount += 1;
      if (snapshot.crossedBook) {
        bucket.crossedBookCount += 1;
        bucket.firstCrossedAt ??= sample.occurredAt ?? null;
      }
      if (snapshot.lockedBook) bucket.lockedBookCount += 1;
      if (snapshot.emptyBook) {
        bucket.emptyBookCount += 1;
        bucket.firstEmptyAt ??= sample.occurredAt ?? null;
      }
      if (snapshot.quotedSpreadBps !== null && Number.isFinite(Number(snapshot.quotedSpreadBps))) {
        bucket.spreadBps.push(Number(snapshot.quotedSpreadBps));
      }
      byInstrument.set(instrumentId, bucket);
    }
  }

  const instruments = Array.from(byInstrument.values())
    .sort((left, right) => left.instrumentId.localeCompare(right.instrumentId))
    .map((bucket) => {
      const spreadBps = bucket.spreadBps.slice().sort((left, right) => left - right);
      const topOfBookPct = pct(bucket.topOfBookCount, bucket.sampleCount);
      const depthPct = pct(bucket.depthCount, bucket.sampleCount);
      const medianQuotedSpreadBps = percentile(spreadBps, 0.5);
      const p95QuotedSpreadBps = percentile(spreadBps, 0.95);
      const failures = [];
      if (bucket.sampleCount === 0) failures.push("no_health_samples");
      if (topOfBookPct < minTopOfBookPct) failures.push(`topOfBookPct ${topOfBookPct.toFixed(2)} < ${minTopOfBookPct}`);
      if (depthPct < minDepthPct) failures.push(`depthPct ${depthPct.toFixed(2)} < ${minDepthPct}`);
      if (medianQuotedSpreadBps !== null && medianQuotedSpreadBps > maxMedianQuotedSpreadBps) {
        failures.push(`medianQuotedSpreadBps ${medianQuotedSpreadBps.toFixed(2)} > ${maxMedianQuotedSpreadBps}`);
      }
      if (p95QuotedSpreadBps !== null && p95QuotedSpreadBps > maxP95QuotedSpreadBps) {
        failures.push(`p95QuotedSpreadBps ${p95QuotedSpreadBps.toFixed(2)} > ${maxP95QuotedSpreadBps}`);
      }
      if (bucket.crossedBookCount > 0) failures.push(`crossedBookCount ${bucket.crossedBookCount} > 0`);
      return {
        instrumentId: bucket.instrumentId,
        status: failures.length === 0 ? "pass" : "warn",
        failures,
        sampleCount: bucket.sampleCount,
        topOfBookPct,
        depthPct,
        medianQuotedSpreadBps,
        p95QuotedSpreadBps,
        crossedBookCount: bucket.crossedBookCount,
        lockedBookCount: bucket.lockedBookCount,
        emptyBookCount: bucket.emptyBookCount,
        firstCrossedAt: bucket.firstCrossedAt,
        firstEmptyAt: bucket.firstEmptyAt,
      };
    });
  const failures = instruments.flatMap((instrument) =>
    instrument.failures.map((failure) => `${instrument.instrumentId}: ${failure}`),
  );
  return {
    schemaVersion: "reef.arena.marketQualitySummary.v0",
    status: failures.length === 0 ? "pass" : "warn",
    failures,
    sampleCount: instruments.reduce((total, instrument) => total + instrument.sampleCount, 0),
    postWarmupSampleCount: postWarmupSamples.length,
    thresholds: {
      minTopOfBookPct,
      minDepthPct,
      maxMedianQuotedSpreadBps,
      maxP95QuotedSpreadBps,
    },
    instruments,
  };
}

function attachLiquidityDiagnostics(botResults, marketQualitySummary, venueReadback) {
  const context = liquidityAttributionContext(botResults, venueReadback);
  return botResults.map((result) => {
    if (result.actorClass !== "house_market_maker") return result;
    return {
      ...result,
      liquidityDiagnostics: liquidityProviderDiagnostics(result, marketQualitySummary, context),
    };
  });
}

function liquidityProviderDiagnostics(result, marketQualitySummary, context) {
  const instruments = liquidityProviderInstruments(result);
  const quoteCoverage = instruments.map((instrumentId) => liquidityInstrumentCoverage(instrumentId, marketQualitySummary));
  const medianSpreadValues = quoteCoverage
    .map((entry) => entry.medianQuotedSpreadBps)
    .filter((value) => value !== null && Number.isFinite(value));
  const p95SpreadValues = quoteCoverage
    .map((entry) => entry.p95QuotedSpreadBps)
    .filter((value) => value !== null && Number.isFinite(value));
  const avgTopOfBookPct = average(quoteCoverage.map((entry) => entry.topOfBookPct));
  const avgDepthPct = average(quoteCoverage.map((entry) => entry.depthPct));
  const thresholds = marketQualitySummary?.thresholds ?? {};
  const fillCount = numberValue(result.tradingMetrics?.executions?.fillCount);
  const inventoryGrossNotional = numberValue(result.tradingMetrics?.inventory?.grossNotional);
  const grossExecutedNotional = numberValue(result.tradingMetrics?.executions?.grossNotional);
  const providerQuoteQuality = providerCurrentQuoteQuality(result, context);
  const flags = [];
  if (instruments.length === 0) flags.push("no-liquidity-instruments");
  if (numberValue(result.tradingMetrics?.orderFlow?.submittedLimitOrders) === 0) flags.push("no-quote-submissions");
  if (fillCount === 0) flags.push("no-liquidity-fills");
  if (avgTopOfBookPct !== null && avgTopOfBookPct < Number(thresholds.minTopOfBookPct ?? 90)) flags.push("low-quote-uptime");
  if (avgDepthPct !== null && avgDepthPct < Number(thresholds.minDepthPct ?? 90)) flags.push("thin-depth");
  const medianQuotedSpreadBps = average(medianSpreadValues);
  const p95QuotedSpreadBps = average(p95SpreadValues);
  if (medianQuotedSpreadBps !== null && medianQuotedSpreadBps > Number(thresholds.maxMedianQuotedSpreadBps ?? 25)) flags.push("wide-median-spread");
  if (p95QuotedSpreadBps !== null && p95QuotedSpreadBps > Number(thresholds.maxP95QuotedSpreadBps ?? 50)) flags.push("wide-p95-spread");
  if (grossExecutedNotional > 0 && inventoryGrossNotional / grossExecutedNotional > 0.5) flags.push("inventory-pressure");
  const adverseSelection = liquidityAdverseSelection(result);
  if (numberValue(adverseSelection.adverseFillCount) > 0) flags.push("adverse-selection-observed");

  return {
    schemaVersion: "reef.arena.liquidityProviderDiagnostics.v1",
    mode: "score-neutral-liquidity-context",
    scoreEffect: result.actorProfile?.scoreEffect ?? "diagnostic-only",
    scoreNeutral: true,
    pointsEffect: 0,
    publicScore: null,
    shadowScore: null,
    status: flags.length === 0 ? "pass" : "warn",
    flags,
    instruments,
    quoteCoverage,
    quoteQuality: {
      attribution: "market-wide-proxy",
      avgTopOfBookPct,
      avgDepthPct,
      medianQuotedSpreadBps,
      p95QuotedSpreadBps,
    },
    providerQuoteQuality,
    orderActivity: {
      submittedLimitOrders: numberValue(result.tradingMetrics?.orderFlow?.submittedLimitOrders),
      modifyCommands: numberValue(result.tradingMetrics?.orderFlow?.modifyCommands),
      cancelCommands: numberValue(result.tradingMetrics?.orderFlow?.cancelCommands),
      cancelReplaceRatio: numberValue(result.conductMetrics?.cancelReplaceRatio),
      maxVenueCommandsPerTick: numberValue(result.conductMetrics?.maxVenueCommandsPerTick),
    },
    fillParticipation: {
      fillCount,
      filledQuantity: numberValue(result.tradingMetrics?.executions?.filledQuantity),
      grossExecutedNotional,
      avgFillPrice: nullableNumber(result.tradingMetrics?.executions?.avgFillPrice),
    },
    attribution: providerLiquidityAttribution(result, context),
    inventory: {
      netQuantityByInstrument: result.tradingMetrics?.inventory?.netQuantityByInstrument ?? {},
      grossNotional: nullableNumber(result.tradingMetrics?.inventory?.grossNotional),
      markPriceSource: result.tradingMetrics?.inventory?.markPriceSource ?? "",
    },
    adverseSelection,
  };
}

function liquidityAdverseSelection(result) {
  const diagnostics = result.tradingMetrics?.adverseSelection;
  if (diagnostics !== undefined && diagnostics !== null) {
    return diagnostics;
  }
  return {
    schemaVersion: "reef.arena.adverseSelectionDiagnostics.v1",
    available: false,
    source: "unavailable",
    reason: "requires participant-scoped fills plus post-fill health sample mids",
  };
}

function liquidityAttributionContext(botResults, venueReadback) {
  const providers = botResults.filter((result) => result.actorClass === "house_market_maker");
  const totals = {
    submittedLimitOrders: sum(providers.map((provider) => provider.tradingMetrics?.orderFlow?.submittedLimitOrders)),
    grossSubmittedQuantity: sum(providers.map((provider) => provider.tradingMetrics?.orderFlow?.grossSubmittedQuantity)),
    grossSubmittedNotional: sum(providers.map((provider) => provider.tradingMetrics?.orderFlow?.grossSubmittedNotional)),
    fillCount: sum(providers.map((provider) => provider.tradingMetrics?.executions?.fillCount)),
    filledQuantity: sum(providers.map((provider) => provider.tradingMetrics?.executions?.filledQuantity)),
    grossExecutedNotional: sum(providers.map((provider) => provider.tradingMetrics?.executions?.grossNotional)),
  };
  const readbackByBotId = new Map();
  for (const entry of venueReadback?.ownOrders ?? []) {
    readbackByBotId.set(entry.botId, entry);
  }
  const source = venueReadback?.skipped === true
    ? "dry-run-trading-metrics"
    : Array.isArray(venueReadback?.ownOrders)
      ? "participant-scoped-readback-and-trading-metrics"
      : "unavailable";
  return {
    schemaVersion: "reef.arena.liquidityAttributionContext.v1",
    source,
    providerCount: providers.length,
    totals,
    readbackByBotId,
  };
}

function providerLiquidityAttribution(result, context) {
  const submittedLimitOrders = numberValue(result.tradingMetrics?.orderFlow?.submittedLimitOrders);
  const grossSubmittedQuantity = numberValue(result.tradingMetrics?.orderFlow?.grossSubmittedQuantity);
  const grossSubmittedNotional = numberValue(result.tradingMetrics?.orderFlow?.grossSubmittedNotional);
  const fillCount = numberValue(result.tradingMetrics?.executions?.fillCount);
  const filledQuantity = numberValue(result.tradingMetrics?.executions?.filledQuantity);
  const grossExecutedNotional = numberValue(result.tradingMetrics?.executions?.grossNotional);
  return {
    schemaVersion: "reef.arena.liquidityProviderAttribution.v1",
    source: context.source,
    orderContribution: {
      submittedLimitOrders,
      submittedLimitOrderSharePct: pct(submittedLimitOrders, context.totals.submittedLimitOrders),
      grossSubmittedQuantity,
      grossSubmittedQuantitySharePct: pct(grossSubmittedQuantity, context.totals.grossSubmittedQuantity),
      grossSubmittedNotional,
      grossSubmittedNotionalSharePct: pct(grossSubmittedNotional, context.totals.grossSubmittedNotional),
    },
    fillContribution: {
      fillCount,
      fillSharePct: pct(fillCount, context.totals.fillCount),
      filledQuantity,
      filledQuantitySharePct: pct(filledQuantity, context.totals.filledQuantity),
      grossExecutedNotional,
      grossExecutedNotionalSharePct: pct(grossExecutedNotional, context.totals.grossExecutedNotional),
    },
    pointsEffect: 0,
  };
}

function providerCurrentQuoteQuality(result, context) {
  const readback = context.readbackByBotId.get(result.botId);
  const currentOrders = readbackOrders(readback?.current);
  const instruments = providerQuoteQualityByInstrument(currentOrders);
  const spreadBps = instruments
    .map((entry) => entry.quotedSpreadBps)
    .filter((value) => value !== null && Number.isFinite(value))
    .sort((left, right) => left - right);
  return {
    schemaVersion: "reef.arena.providerQuoteQuality.v1",
    source: readback === undefined ? "unavailable" : "participant-current-orders",
    attribution: readback === undefined ? "unavailable" : "provider-owned-current-orders",
    currentOrderCount: currentOrders.length,
    instrumentCount: instruments.length,
    medianQuotedSpreadBps: percentile(spreadBps, 0.5),
    p95QuotedSpreadBps: percentile(spreadBps, 0.95),
    instruments,
    limitations: [
      "current-order readback is point-in-time; market-wide quote coverage still comes from health samples",
      "adverse selection requires post-fill price path attribution",
    ],
  };
}

function readbackOrders(response) {
  const body = response?.body ?? {};
  if (Array.isArray(body.orders)) return body.orders;
  if (Array.isArray(body)) return body;
  return [];
}

function providerQuoteQualityByInstrument(orders) {
  const byInstrument = new Map();
  for (const order of orders) {
    const instrumentId = String(order.instrumentId ?? "");
    if (instrumentId.length === 0) continue;
    const side = String(order.side ?? "").toUpperCase();
    const status = String(order.status ?? order.currentStatus ?? "").toUpperCase();
    const remainingQuantity = numberValue(order.remainingQuantityUnits ?? order.remainingQuantity ?? order.quantityUnits ?? order.quantity);
    if (!["OPEN", "PARTIALLY_FILLED", ""].includes(status) || remainingQuantity <= 0) continue;
    const price = marketPriceValue(order.limitPrice ?? order.price);
    if (price === null) continue;
    const bucket = byInstrument.get(instrumentId) ?? { instrumentId, bidPrices: [], askPrices: [] };
    if (side === "BUY") bucket.bidPrices.push(price);
    if (side === "SELL") bucket.askPrices.push(price);
    byInstrument.set(instrumentId, bucket);
  }
  return Array.from(byInstrument.values())
    .sort((left, right) => left.instrumentId.localeCompare(right.instrumentId))
    .map((bucket) => {
      const bidPrice = bucket.bidPrices.length === 0 ? null : Math.max(...bucket.bidPrices);
      const askPrice = bucket.askPrices.length === 0 ? null : Math.min(...bucket.askPrices);
      const midPrice = bidPrice !== null && askPrice !== null ? (bidPrice + askPrice) / 2 : null;
      const quotedSpread = bidPrice !== null && askPrice !== null ? askPrice - bidPrice : null;
      const quotedSpreadBps = quotedSpread !== null && midPrice !== null && midPrice > 0
        ? (quotedSpread / midPrice) * 10000
        : null;
      const flags = [];
      if (bidPrice === null) flags.push("missing-bid");
      if (askPrice === null) flags.push("missing-ask");
      if (quotedSpread !== null && quotedSpread < 0) flags.push("crossed-provider-quotes");
      return {
        instrumentId: bucket.instrumentId,
        status: flags.length === 0 ? "pass" : "warn",
        flags,
        bidPrice,
        askPrice,
        quotedSpread,
        quotedSpreadBps,
      };
    });
}

function summarizeLiquidityProviders(botResults, marketQualitySummary) {
  const providers = botResults.filter((result) => result.actorClass === "house_market_maker");
  const activeProviders = providers.filter((provider) =>
    numberValue(provider.tradingMetrics?.orderFlow?.submittedLimitOrders) > 0 ||
    numberValue(provider.tradingMetrics?.commands?.submitted) > 0
  );
  const primaryInstruments = liquidityPrimaryInstruments(marketQualitySummary);
  const instrumentCoverage = primaryInstruments.map((instrumentId) => {
    const providerIds = providers
      .filter((provider) => liquidityProviderInstruments(provider).includes(instrumentId))
      .map((provider) => provider.botId)
      .sort();
    const marketQuality = liquidityInstrumentCoverage(instrumentId, marketQualitySummary);
    const flags = [];
    if (providerIds.length === 0) flags.push("missing-liquidity-provider");
    if (marketQuality.topOfBookPct < Number(marketQualitySummary?.thresholds?.minTopOfBookPct ?? 90)) flags.push("low-quote-uptime");
    if (marketQuality.depthPct < Number(marketQualitySummary?.thresholds?.minDepthPct ?? 90)) flags.push("thin-depth");
    if (
      marketQuality.medianQuotedSpreadBps !== null &&
      marketQuality.medianQuotedSpreadBps > Number(marketQualitySummary?.thresholds?.maxMedianQuotedSpreadBps ?? 25)
    ) {
      flags.push("wide-median-spread");
    }
    if (marketQuality.crossedBookCount > 0) flags.push("crossed-book");
    return {
      instrumentId,
      providerCount: providerIds.length,
      providerIds,
      marketQuality,
      status: flags.length === 0 ? "pass" : "warn",
      flags,
    };
  });
  const totals = {
    providerCount: providers.length,
    activeProviderCount: activeProviders.length,
    submittedLimitOrders: sum(providers.map((provider) => provider.tradingMetrics?.orderFlow?.submittedLimitOrders)),
    modifyCommands: sum(providers.map((provider) => provider.tradingMetrics?.orderFlow?.modifyCommands)),
    cancelCommands: sum(providers.map((provider) => provider.tradingMetrics?.orderFlow?.cancelCommands)),
    fillCount: sum(providers.map((provider) => provider.tradingMetrics?.executions?.fillCount)),
    filledQuantity: sum(providers.map((provider) => provider.tradingMetrics?.executions?.filledQuantity)),
    grossExecutedNotional: Number(sum(providers.map((provider) => provider.tradingMetrics?.executions?.grossNotional)).toFixed(6)),
    inventoryGrossNotional: Number(sum(providers.map((provider) => provider.tradingMetrics?.inventory?.grossNotional)).toFixed(6)),
  };
  const flags = [];
  if (providers.length === 0) flags.push("missing-liquidity-provider");
  if (providers.length > 0 && activeProviders.length === 0) flags.push("no-active-liquidity-provider");
  if (totals.fillCount === 0) flags.push("no-liquidity-fills");
  for (const coverage of instrumentCoverage) {
    for (const flag of coverage.flags) {
      flags.push(`${coverage.instrumentId}:${flag}`);
    }
  }
  return {
    schemaVersion: "reef.arena.liquiditySummary.v1",
    mode: "score-neutral-liquidity-context",
    scoreNeutral: true,
    pointsEffect: 0,
    status: flags.length === 0 ? "pass" : "warn",
    flags,
    totals,
    instruments: instrumentCoverage,
    providerDiagnostics: providers.map((provider) => ({
      botId: provider.botId,
      status: provider.liquidityDiagnostics?.status ?? "unknown",
      flags: provider.liquidityDiagnostics?.flags ?? [],
      instruments: provider.liquidityDiagnostics?.instruments ?? [],
      quoteQuality: provider.liquidityDiagnostics?.quoteQuality ?? {},
      providerQuoteQuality: provider.liquidityDiagnostics?.providerQuoteQuality ?? {},
      fillParticipation: provider.liquidityDiagnostics?.fillParticipation ?? {},
      attribution: provider.liquidityDiagnostics?.attribution ?? {},
      adverseSelection: provider.liquidityDiagnostics?.adverseSelection ?? {},
      inventory: provider.liquidityDiagnostics?.inventory ?? {},
      pointsEffect: 0,
    })),
    notes: "Liquidity diagnostics are report-only and do not create point gains or losses for house actors.",
  };
}

function liquidityProviderInstruments(result) {
  const instruments = new Set([
    ...Object.keys(result.tradingMetrics?.orderFlow?.byInstrument ?? {}),
    ...Object.keys(result.tradingMetrics?.executions?.byInstrument ?? {}),
    ...Object.keys(result.tradingMetrics?.inventory?.netQuantityByInstrument ?? {}),
  ]);
  return Array.from(instruments).filter((instrumentId) => instrumentId.length > 0 && instrumentId !== "unknown").sort();
}

function liquidityPrimaryInstruments(marketQualitySummary) {
  const configured = mode.healthTargets?.primaryInstruments ?? mode.instruments ?? [];
  const fromMarketQuality = (marketQualitySummary?.instruments ?? []).map((instrument) => instrument.instrumentId);
  return Array.from(new Set([...configured, ...fromMarketQuality])).filter((instrumentId) => String(instrumentId).length > 0).sort();
}

function liquidityInstrumentCoverage(instrumentId, marketQualitySummary) {
  const match = (marketQualitySummary?.instruments ?? []).find((instrument) => instrument.instrumentId === instrumentId);
  return {
    instrumentId,
    status: match?.status ?? "unknown",
    sampleCount: numberValue(match?.sampleCount),
    topOfBookPct: nullableNumber(match?.topOfBookPct) ?? 0,
    depthPct: nullableNumber(match?.depthPct) ?? 0,
    medianQuotedSpreadBps: nullableNumber(match?.medianQuotedSpreadBps),
    p95QuotedSpreadBps: nullableNumber(match?.p95QuotedSpreadBps),
    crossedBookCount: numberValue(match?.crossedBookCount),
    lockedBookCount: numberValue(match?.lockedBookCount),
    emptyBookCount: numberValue(match?.emptyBookCount),
    failures: match?.failures ?? [],
  };
}

async function collectVenueReadback(botResults) {
  if (config.submitMode !== "live") {
    return { mode: config.submitMode, skipped: true };
  }
  const baseUrl = config.venueUrl.replace(/\/$/, "");
  const availability = await waitForDataAvailability(baseUrl);
  const snapshots = await Promise.all((mode.instruments ?? ["AAPL"]).map(async (instrumentId) => ({
      instrumentId,
      ...(await getJson(`${baseUrl}/api/v1/market-data/snapshots/${encodeURIComponent(instrumentId)}`, readbackHeaders())),
    })));
  const ownOrders = await mapWithConcurrency(botResults, 6, async (result) => {
    const participantId = participantIdForBot(result);
    const [current, history, fills] = await Promise.all([
      getJson(`${baseUrl}/api/v1/orders/current?participantId=${encodeURIComponent(participantId)}&limit=50`, readbackHeaders(participantId)),
      getJson(`${baseUrl}/api/v1/orders/history?participantId=${encodeURIComponent(participantId)}&limit=50`, readbackHeaders(participantId)),
      getJson(`${baseUrl}/api/v1/orders/fills?participantId=${encodeURIComponent(participantId)}&limit=200`, readbackHeaders(participantId)),
    ]);
    return {
      botId: result.botId,
      participantId,
      current,
      history,
      fills,
    };
  });
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
    executionSummary: summarizeVenueReadbackExecutions(ownOrders),
  };
}

async function mapWithConcurrency(values, concurrency, action) {
  const results = new Array(values.length);
  let nextIndex = 0;
  const workerCount = Math.min(Math.max(1, concurrency), values.length);
  await Promise.all(Array.from({ length: workerCount }, async () => {
    while (nextIndex < values.length) {
      const index = nextIndex;
      nextIndex += 1;
      results[index] = await action(values[index], index);
    }
  }));
  return results;
}

function summarizeVenueReadbackExecutions(ownOrders) {
  const byInstrument = {};
  const byBotId = {};
  const byRole = {};
  let fillCount = 0;
  let filledQuantity = 0;
  let filledNotional = 0;

  for (const entry of ownOrders ?? []) {
    const fills = Array.isArray(entry.fills?.body?.fills) ? entry.fills.body.fills : [];
    const bot = selectedBots.find((candidate) => candidate.botId === entry.botId);
    const role = bot?.role ?? "unknown";
    for (const fill of fills) {
      const quantity = numberValue(fill.quantityUnits);
      const price = priceFromExecutionPrice(fill.executionPrice);
      fillCount += 1;
      filledQuantity += quantity;
      if (price !== undefined) {
        filledNotional += quantity * price;
      }
      const instrumentId = typeof fill.instrumentId === "string" && fill.instrumentId.length > 0 ? fill.instrumentId : "unknown";
      incrementExecutionBucket(byInstrument, instrumentId, quantity, price);
      incrementExecutionBucket(byBotId, entry.botId, quantity, price);
      incrementExecutionBucket(byRole, role, quantity, price);
    }
  }

  return {
    schemaVersion: "reef.arena.executionSummary.v0",
    source: "venue-readback-order-fills",
    fillCount,
    filledQuantity,
    filledNotional: Number(filledNotional.toFixed(6)),
    avgFillPrice: filledQuantity === 0 ? null : Number((filledNotional / filledQuantity).toFixed(6)),
    byInstrument: sortedExecutionBuckets(byInstrument),
    byBotId: sortedExecutionBuckets(byBotId),
    byRole: sortedExecutionBuckets(byRole),
  };
}

function incrementExecutionBucket(buckets, key, quantity, price) {
  const bucket = buckets[key] ?? { fillCount: 0, filledQuantity: 0, filledNotional: 0 };
  bucket.fillCount += 1;
  bucket.filledQuantity += quantity;
  if (price !== undefined) {
    bucket.filledNotional += quantity * price;
  }
  buckets[key] = bucket;
}

function sortedExecutionBuckets(buckets) {
  return Object.fromEntries(
    Object.entries(buckets)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, bucket]) => [
        key,
        {
          fillCount: bucket.fillCount,
          filledQuantity: bucket.filledQuantity,
          filledNotional: Number(bucket.filledNotional.toFixed(6)),
          avgFillPrice: bucket.filledQuantity === 0 ? null : Number((bucket.filledNotional / bucket.filledQuantity).toFixed(6)),
        },
      ]),
  );
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

async function drainProjectionsAfterSubmission(submission) {
  if (
    config.submitMode !== "live" ||
    !config.requireProjectionDrain ||
    config.projectionDrainCadence !== "per-submission" ||
    Number(submission?.submitted ?? 0) === 0
  ) {
    return submission;
  }
  const startedAt = performance.now();
  const availability = await waitForDataAvailability(config.venueUrl.replace(/\/$/, ""));
  return {
    ...submission,
    projectionDrain: {
      required: true,
      drained: availabilityDrained(availability),
      statusCode: availability.statusCode,
      elapsedMs: performance.now() - startedAt,
    },
  };
}

async function drainProjectionsAfterScheduledEvent(tickReports) {
  if (
    config.submitMode !== "live" ||
    !config.requireProjectionDrain ||
    config.projectionDrainCadence !== "scheduled-event" ||
    !tickReports.some((tick) => Number(tick.submission?.submitted ?? 0) > 0)
  ) {
    return {
      required: false,
      drained: null,
      elapsedMs: 0,
    };
  }
  const startedAt = performance.now();
  const availability = await waitForDataAvailability(config.venueUrl.replace(/\/$/, ""));
  return {
    required: true,
    drained: availabilityDrained(availability),
    statusCode: availability.statusCode,
    elapsedMs: performance.now() - startedAt,
  };
}

function availabilityDrained(availability) {
  const projections = availability.body?.projections;
  return Array.isArray(projections) && projections.every((projection) => Number(projection.lag ?? 0) === 0);
}

async function assertLiveMarketQualityProjectors() {
  if (
    config.submitMode !== "live" ||
    !config.requireProjectionDrain ||
    config.skipProjectorPreflight ||
    config.projectorPreflight === "skip"
  ) {
    return;
  }

  const statuses = await Promise.all([
    readProjectorStatus("order-lifecycle", "/internal/order-lifecycle/projector/status"),
    readProjectorStatus("market-data", "/internal/market-data/projector/status"),
  ]);
  const failures = statuses.filter((status) => status.ok !== true);
  if (failures.length === 0) {
    return;
  }

  const details = failures.map((failure) => `${failure.name}: ${failure.reason}`).join("; ");
  throw new Error(
    "live arena market-quality preflight failed: " +
    `${details}. Restart the stack with ` +
    "`ORDER_LIFECYCLE_PROJECTOR_ENABLED=true MARKET_DATA_PROJECTOR_ENABLED=true make dev-reset` " +
    "or pass --skip-projector-preflight for a non-scoring/debug run.",
  );
}

async function readProjectorStatus(name, path) {
  if (config.projectorPreflight !== "docker") {
    const httpStatus = await readProjectorStatusHttp(name, path);
    if (httpStatus.ok === true || config.projectorPreflight === "http") {
      return httpStatus;
    }
  }
  if (config.projectorPreflight !== "http") {
    return readProjectorStatusDocker(name, path);
  }
  return { name, ok: false, reason: "projector status unavailable" };
}

async function readProjectorStatusHttp(name, path) {
  try {
    const response = await getJson(`${config.venueUrl.replace(/\/$/, "")}${path}`, readbackHeaders());
    return projectorStatusResult(name, response.statusCode, response.body, "http");
  } catch (error) {
    return { name, ok: false, reason: `http status request failed: ${error.message}` };
  }
}

function readProjectorStatusDocker(name, path) {
  const projectName = env("COMPOSE_PROJECT_NAME", "reef");
  const result = spawnSync("docker", [
    "compose",
    "-p",
    projectName,
    "exec",
    "-T",
    "platform-projector-0",
    "curl",
    "-s",
    `http://127.0.0.1:8080${path}`,
  ], {
    cwd: repoRoot,
    encoding: "utf8",
    timeout: 10000,
  });
  if (result.status !== 0) {
    return {
      name,
      ok: false,
      reason: `docker status request failed: ${(result.stderr || result.stdout || `exit ${result.status}`).trim()}`,
    };
  }
  return projectorStatusResult(name, 200, safeJson(result.stdout), `docker compose -p ${projectName}`);
}

function projectorStatusResult(name, statusCode, body, source) {
  if (statusCode < 200 || statusCode >= 300) {
    return { name, ok: false, reason: `${source} returned ${statusCode}: ${JSON.stringify(body)}` };
  }
  if (body?.enabled !== true) {
    return { name, ok: false, reason: `${source} reports enabled=${JSON.stringify(body?.enabled)} body=${JSON.stringify(body)}` };
  }
  if (body?.role !== undefined && body.role !== "projector") {
    return { name, ok: false, reason: `${source} reports role=${JSON.stringify(body.role)}` };
  }
  return { name, ok: true, source, body };
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
  operations.push(await postArenaOk(baseUrl, "/admin/v1/arena/runs", {
    runId: config.runId,
    modeId: mode.modeId,
    scenarioId: mode.scenarioId,
    seed: Number(mode.seed ?? 0),
    policyVersion: mode.riskPolicyVersion ?? "arena-risk-v0",
    admissionWindowId: config.admissionWindowId,
    rosterSnapshotId: config.rosterSnapshotId,
    rosterSnapshotHash: config.rosterSnapshotHash,
    seedSetHash,
    actorProfileVersion: actorProfileCatalog.version,
    actorProfileHash: actorProfileCatalog.contentHash,
    riskPolicyHash,
    policyEnvelopeHash: report.policyEnvelopeHash,
    scoringPolicyVersion: mode.scoringPolicyVersion,
    scoringPolicyHash: scoringPolicy.contentHash,
    economicPolicyVersion: mode.economicPolicyVersion,
    economicPolicyHash: economicPolicy.contentHash,
    botVersions,
    actorId: config.actorId,
    correlationId,
  }, { allowAlreadyExists: true }));
  operations.push(await postArenaOk(baseUrl, "/admin/v1/arena/runs/status", {
    runId: config.runId,
    status: "running",
    actorId: config.actorId,
    correlationId,
  }, { allowInvalidTransition: true }));
  for (const result of report.botResults) {
    const finalEquity = integerMetric(result.finalEquityDiagnostic, result.score);
    const realizedPnl = integerMetric(
      result.scoreBreakdown?.diagnostics?.realizedPnl,
      0,
    );
    operations.push(await postArenaOk(baseUrl, "/admin/v1/arena/run-bot-results", {
      runId: config.runId,
      botId: result.botId,
      versionId: result.versionId,
      scoringPolicyVersion: mode.scoringPolicyVersion,
      scoringPolicyHash: scoringPolicy.contentHash,
      policyEnvelopeHash: report.policyEnvelopeHash,
      finalEquity,
      realizedPnl,
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
    operations.push(await postArenaOk(baseUrl, "/admin/v1/arena/run-enforcement-events", {
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
      operations.push(await postArenaOk(baseUrl, "/admin/v1/arena/bot-versions/transition", {
        botId: event.botId,
        versionId: event.versionId,
        status: "quarantine",
        reason: `arena freeze ${config.runId}: ${event.reasonCode}`,
        actorId: config.actorId,
        correlationId,
      }, { allowInvalidTransition: true }));
    }
  }

  operations.push(await postArenaOk(baseUrl, "/admin/v1/arena/runs/status", {
    runId: config.runId,
    status: report.status === "completed" || report.status === "completed_with_freezes" || report.status === "completed_with_warnings" ? "completed" : "failed",
    actorId: config.actorId,
    correlationId,
  }, { allowInvalidTransition: true }));

  const rawResults = await getArenaJson(baseUrl, `/admin/v1/arena/run-bot-results?runId=${encodeURIComponent(config.runId)}&actorId=${encodeURIComponent(config.actorId)}`);
  const rawEnforcementEvents = await getArenaJson(baseUrl, `/admin/v1/arena/run-enforcement-events?runId=${encodeURIComponent(config.runId)}&actorId=${encodeURIComponent(config.actorId)}`);
  const leaderboard = await getArenaJson(
    baseUrl,
    `/admin/v1/arena/leaderboard?modeId=${encodeURIComponent(mode.modeId)}&scoringPolicyVersion=${encodeURIComponent(mode.scoringPolicyVersion)}&limit=50&actorId=${encodeURIComponent(config.actorId)}`,
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

function integerMetric(value, fallback) {
  const numeric = Number(value);
  return Math.round(value !== null && value !== undefined && Number.isFinite(numeric) ? numeric : Number(fallback));
}

async function ensureArenaBot(baseUrl, bot, correlationId) {
  return await postArenaOk(baseUrl, "/admin/v1/arena/bots", {
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
  const version = await postArenaOk(baseUrl, "/admin/v1/arena/bot-versions", {
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
    await postArenaOk(baseUrl, "/admin/v1/arena/bot-versions/transition", {
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
  const response = await postJson(`${baseUrl}${path}`, payload, arenaAdminHeaders(payload.correlationId));
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
  return getJson(`${baseUrl}${path}`, arenaAdminHeaders(`${config.runId}-persist`));
}

function arenaAdminHeaders(correlationId) {
  return {
    ...(config.arenaAdminApiToken.trim() !== "" ? { Authorization: `Bearer ${config.arenaAdminApiToken}` } : {}),
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
  const headers = setupAdminHeaders(`${config.runId}-reference-seed`);
  for (const instrumentId of mode.instruments ?? ["AAPL"]) {
    await postSetupOk("/admin/v1/reference/instruments", {
      instrumentId,
      symbol: instrumentId,
      assetClass: "US_EQ",
      currency: "USD",
    }, headers);
  }
  for (const bot of bots) {
    const identityKey = venueIdentityKey(bot);
    await postSetupOk("/admin/v1/reference/participants", {
      participantId: participantIdForIdentity(identityKey),
      name: `Arena ${bot.botId}`,
    }, headers);
    await postSetupOk("/admin/v1/reference/accounts", {
      accountId: accountIdForIdentity(identityKey),
      participantId: participantIdForIdentity(identityKey),
      accountType: bot.riskProfile.assetLedgerMode === "bypass" ? "HOUSE" : "CUSTOMER",
    }, headers);
    await postSetupOk("/admin/v1/auth/roles", {
      roleId: "order_trader",
      permissions: "order.submit,order.cancel,order.modify",
    }, headers);
    await postSetupOk("/admin/v1/auth/actor-roles", {
      actorId: actorIdForIdentity(identityKey),
      roleId: "order_trader",
    }, headers);
  }
}

function setupAdminHeaders(correlationId) {
  return {
    ...(config.adminApiToken.trim() !== "" ? { Authorization: `Bearer ${config.adminApiToken}` } : {}),
    "X-Reef-Actor-Id": config.actorId,
    "X-Correlation-Id": correlationId,
  };
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
  constructor(workerId, compartment, isolation) {
    this.workerId = workerId;
    this.compartment = compartment;
    this.isolation = isolation;
    this.nextId = 1;
    this.pending = new Map();
    this.buffer = "";
    this.stderrOutputBytes = 0;
    this.unsolicitedOutputBytes = 0;
  }

  async start() {
    const workerScript = new URL("./arena-runner-pool-worker.mjs", import.meta.url).pathname;
    const workerCommand = ["bun", "scripts/dev/arena-runner-pool-worker.mjs", `--worker-id=${this.workerId}`, `--compartment=${this.compartment}`];
    const command = this.isolation === "container" ? "docker" : "bun";
    const commandArgs = this.isolation === "container"
      ? hostedBotContainerArgs({ repoRoot, command: workerCommand, network: runnerContainerNetwork })
      : [workerScript, `--worker-id=${this.workerId}`, `--compartment=${this.compartment}`];
    this.child = spawn(
      command,
      commandArgs,
      { cwd: repoRoot, stdio: ["pipe", "pipe", "pipe"], env: this.isolation === "container" ? process.env : hostedWorkerProcessEnv() },
    );
    this.child.stdout.setEncoding("utf8");
    this.child.stderr.setEncoding("utf8");
    this.child.stdout.on("data", (chunk) => {
      this.handleStdout(chunk);
    });
    this.child.stderr.on("data", (chunk) => {
      this.stderrOutputBytes += Buffer.byteLength(chunk);
      if (this.stderrOutputBytes > config.runnerMaxOutputBytes) {
        this.failOutputLimit();
        return;
      }
      process.stderr.write(`[${this.workerId}] ${chunk}`);
    });
    this.child.on("close", (code) => {
      for (const pending of this.pending.values()) {
        pending.reject(new Error(`${this.workerId} exited with code ${code ?? "unknown"}`));
      }
      this.pending.clear();
    });
    await this.request({ type: "heartbeat" });
  }

  failOutputLimit() {
    if (this.child !== undefined && !this.child.killed) {
      this.child.kill("SIGKILL");
    }
    for (const pending of this.pending.values()) {
      pending.reject(new Error(`${this.workerId} exceeded output limit ${config.runnerMaxOutputBytes} bytes`));
    }
    this.pending.clear();
  }

  request(message, timeoutMs = 10000) {
    const id = `${this.workerId}-${this.nextId}`;
    this.nextId += 1;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pending.delete(id);
        if (this.child !== undefined && !this.child.killed) {
          this.child.kill("SIGKILL");
        }
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
        if (Buffer.byteLength(line) > config.runnerMaxOutputBytes) {
          this.failOutputLimit();
          return;
        }
        let response;
        try {
          response = JSON.parse(line);
        } catch {
          this.failOutputLimit();
          return;
        }
        const pending = this.pending.get(response.id);
        if (pending !== undefined) {
          this.pending.delete(response.id);
          pending.resolve(response);
        } else {
          this.unsolicitedOutputBytes += Buffer.byteLength(line) + 1;
          if (this.unsolicitedOutputBytes > config.runnerMaxOutputBytes) {
            this.failOutputLimit();
            return;
          }
        }
      }
      newlineIndex = this.buffer.indexOf("\n");
    }
    if (Buffer.byteLength(this.buffer) > config.runnerMaxOutputBytes) {
      this.failOutputLimit();
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
      ...actorProfileRuntimeConfig(bot),
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
    actorProfiles: summarizeActorProfiles(selectedBotsForPlan),
  };
}

function summarizeActorProfiles(bots) {
  const byActorClass = {};
  const byDifficultyBucket = {};
  const profiles = bots.map((bot) => {
    const profile = bot.actorProfile;
    increment(byActorClass, profile.actorClass);
    increment(byDifficultyBucket, profile.difficultyBucket);
    return {
      botId: bot.botId,
      role: bot.role,
      actorClass: profile.actorClass,
      profileId: profile.profileId,
      profileVersion: profile.profileVersion,
      profileHash: profile.profileHash,
      difficultyBucket: profile.difficultyBucket,
      scoreEffect: profile.scoreEffect,
    };
  });
  return {
    schemaVersion: "reef.arena.actorProfileSummary.v1",
    catalogId: actorProfileCatalog.catalogId,
    catalogVersion: actorProfileCatalog.version,
    byActorClass: sortedRecord(byActorClass),
    byDifficultyBucket: sortedRecord(byDifficultyBucket),
    profiles,
    npcDifficultyBuckets: npcDifficultyBuckets(bots),
  };
}

function npcDifficultyBuckets(bots) {
  return Array.from(new Set(
    bots
      .filter((bot) => bot.actorProfile?.actorClass === "npc_flow")
      .map((bot) => bot.actorProfile.difficultyBucket),
  )).sort();
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

function actorProfileRuntimeConfig(bot) {
  const profile = bot.actorProfile;
  if (profile === undefined) {
    return {};
  }
  const values = {
    "actorProfile.profileId": profile.profileId,
    "actorProfile.profileVersion": profile.profileVersion,
    "actorProfile.actorClass": profile.actorClass,
    "actorProfile.difficultyBucket": profile.difficultyBucket,
    "actorProfile.scoreEffect": profile.scoreEffect,
    "actorProfile.profileHash": profile.profileHash,
  };
  for (const [key, value] of Object.entries(profile.params ?? {})) {
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
      values[`actorProfile.${key}`] = value;
    }
  }
  return values;
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

function workerVenueUrl() {
  if (config.runnerIsolation !== "container") {
    return config.venueUrl;
  }
  return hostedBotContainerReachableUrl(config.venueUrl);
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

function average(values) {
  const numbers = values
    .map((value) => nullableNumber(value))
    .filter((value) => value !== null);
  return numbers.length === 0 ? null : Number((sum(numbers) / numbers.length).toFixed(6));
}

function sum(values) {
  return values.reduce((total, value) => total + numberValue(value), 0);
}

function nullableNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function midpoint(bid, ask) {
  if (bid === null || ask === null) return null;
  return (bid + ask) / 2;
}

function pct(count, total) {
  return total > 0 ? Number(((count / total) * 100).toFixed(6)) : 0;
}

function ratio(count, total) {
  return total > 0 ? Number((count / total).toFixed(6)) : 0;
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

function policyArtifact(policy) {
  const content = Object.fromEntries(
    Object.entries(policy).filter(([key]) => key !== "contentHash" && key !== "profilesById"),
  );
  return {
    artifactId: policy.policyId ?? policy.catalogId,
    version: policy.version,
    contentHash: policy.contentHash,
    content,
  };
}

function stableHash(value) {
  return createHash("sha256").update(stableStringify(value)).digest("hex");
}

function stableStringify(value) {
  if (Array.isArray(value)) {
    return `[${value.map((entry) => stableStringify(entry)).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
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
