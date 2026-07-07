import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { performance } from "node:perf_hooks";

const repoRoot = new URL("../../", import.meta.url).pathname;
const args = process.argv.slice(2);
const config = {
  mode: stringOption("--mode", "packages/scenario-definitions/arena/equity-sprint.v1.json"),
  compartment: stringOption("--compartment", "ses"),
  out: stringOption("--out", "/tmp/reef-arena-local-tick-run.json"),
};

if (!["vm", "ses"].includes(config.compartment)) {
  throw new Error(`unsupported --compartment=${config.compartment}; expected vm or ses`);
}

const mode = readJson(config.mode);
const catalog = readJson(mode.catalogPath);
const riskProfiles = catalog.riskProfiles ?? {};
const baseFixture = readJson("packages/bot-sdk/fixtures/aapl-multi-tick.json");
const selectedBots = mode.botRefs.map((botId) => {
  const entry = catalog.bots.find((bot) => bot.botId === botId);
  if (entry === undefined) {
    throw new Error(`mode references unknown bot ${botId}`);
  }
  const riskProfile = riskProfiles[entry.riskProfile];
  if (riskProfile === undefined) {
    throw new Error(`bot ${botId} references unknown risk profile ${entry.riskProfile}`);
  }
  return { ...entry, riskProfile };
});

const outDir = mkdtempSync(join(tmpdir(), "reef-arena-local-tick-"));
const worker = new RunnerWorker("arena-tick-worker-0", config.compartment);
const startedAt = performance.now();

try {
  await worker.start();
  const bots = selectedBots.map((bot) => ({ ...bot, artifact: buildArtifact(bot.entryPath, bot.runnerKey) }));
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
  for (const bot of bots) {
    const result = await runBotSession(bot);
    sessionReports.push(result);
    enforcementEvents.push(...enforceBot(bot, result));
  }

  const botResults = scoreBots(sessionReports, enforcementEvents);
  const report = buildReport({ botResults, enforcementEvents, sessionReports, elapsedMs: performance.now() - startedAt });
  mkdirSync(dirname(config.out), { recursive: true });
  writeFileSync(config.out, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`arena local tick run complete: ${resolve(config.out)}`);
  console.log(
    `status=${report.status} bots=${botResults.length} ticks=${report.totals.ticks} venueCommands=${report.totals.venueCommands} freezes=${enforcementEvents.filter((event) => event.decision === "freeze").length}`,
  );
  for (const entry of report.leaderboard) {
    console.log(`rank=${entry.rank} bot=${entry.botId} score=${entry.score} disqualified=${entry.disqualified}`);
  }
} finally {
  await worker.shutdown();
}

async function runBotSession(bot) {
  const fixture = fixtureForBot(bot);
  const sessionId = `${mode.modeId}-${bot.runnerKey}-${Date.now()}`;
  const start = await worker.request({ type: "startSession", botKey: bot.runnerKey, sessionId, fixture });
  if (!start.ok) {
    return { bot, sessionId, start, ticks: [], stop: undefined };
  }
  const ticks = [];
  for (const tick of fixture.ticks.slice(0, mode.ticks)) {
    ticks.push(await worker.request({ type: "runTick", sessionId, tick }));
  }
  const stop = await worker.request({ type: "stopSession", sessionId });
  return { bot, sessionId, start, ticks, stop };
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
    runId: `${mode.modeId}-${mode.version}`,
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
      scoreEligible: session.bot.scoreEligible,
      publicLeaderboard: session.bot.publicLeaderboard,
      ticksRun: counters.ticks,
      actionsProposed: counters.actions,
      venueCommands: counters.venueCommands,
      failedTicks: counters.failedTicks,
      latencyP95Ms: counters.latencyP95Ms,
      freezeCount,
      disqualified: freezeCount > 0,
      score,
    };
  });
}

function buildReport({ botResults, enforcementEvents, sessionReports, elapsedMs }) {
  const tickResults = sessionReports.flatMap((session) => session.ticks);
  const totals = tickResults.reduce(
    (acc, tick) => {
      acc.ticks += 1;
      acc.failedTicks += tick.ok ? 0 : 1;
      acc.actions += tick.actions?.length ?? 0;
      acc.venueCommands += tick.venueCommands?.length ?? 0;
      acc.dataCalls += Number(tick.dataCalls ?? 0);
      return acc;
    },
    { ticks: 0, failedTicks: 0, actions: 0, venueCommands: 0, dataCalls: 0 },
  );
  return {
    schemaVersion: "reef.arena.localTickRun.v0",
    generatedAt: new Date().toISOString(),
    mode: {
      modeId: mode.modeId,
      version: mode.version,
      seed: mode.seed,
      scoringPolicyVersion: mode.scoringPolicyVersion,
      riskPolicyVersion: mode.riskPolicyVersion,
    },
    runnerProfile: { compartment: config.compartment, workerCount: 1 },
    status: enforcementEvents.some((event) => event.decision === "freeze") ? "completed_with_freezes" : "completed",
    elapsedMs,
    totals,
    enforcementEvents,
    botResults,
    leaderboard: botResults
      .filter((result) => result.scoreEligible)
      .slice()
      .sort((left, right) => Number(left.disqualified) - Number(right.disqualified) || right.score - left.score || left.botId.localeCompare(right.botId))
      .map((result, index) => ({ rank: index + 1, ...result })),
    sessionReports,
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
      acc.dataCalls += Number(tick.dataCalls ?? 0);
      acc.maxActionsPerTick = Math.max(acc.maxActionsPerTick, tick.actions?.length ?? 0);
      return acc;
    },
    { ticks: 0, failedTicks: 0, actions: 0, venueCommands: 0, dataCalls: 0, maxActionsPerTick: 0, latencyP95Ms: percentile(latencies, 0.95) },
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
    runId: `${mode.modeId}-${mode.version}-${bot.runnerKey}`,
    venueSessionId: mode.venueSessionId,
    botId: bot.botId,
    botVersion: bot.versionId,
    actorId: `actor-${bot.runnerKey}`,
    participantId: `participant-${bot.runnerKey}`,
    accountId: `account-${bot.runnerKey}`,
    correlationId: `${mode.modeId}-${bot.runnerKey}`,
  };
  if (bot.runnerKey === "multi-symbol" || bot.runnerKey === "technical") {
    return withMultiSymbolData(fixture);
  }
  return fixture;
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

function readJson(path) {
  return JSON.parse(readFileSync(resolve(repoRoot, path), "utf8"));
}

function stringOption(name, fallback) {
  const raw = optionValue(name);
  return raw === undefined ? fallback : raw;
}

function optionValue(name) {
  const arg = args.find((candidate) => candidate.startsWith(`${name}=`));
  return arg === undefined ? undefined : arg.slice(name.length + 1);
}
