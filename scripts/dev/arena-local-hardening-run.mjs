import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const args = process.argv.slice(2);

const config = {
  durationSeconds: numberOption("--duration-seconds", 180),
  mode: stringOption("--mode", "packages/scenario-definitions/arena/equity-multi-local.v1.json"),
  venueUrl: stringOption("--venue-url", "http://127.0.0.1:8080"),
  arenaAdminUrl: stringOption("--arena-admin-url", stringOption("--venue-url", "http://127.0.0.1:8080")),
  compartment: stringOption("--compartment", "ses"),
  out: stringOption("--out", "/tmp/reef-arena-local-hardening.json"),
  summaryOut: stringOption("--summary-out", ""),
  projectionDrainTimeoutMs: numberOption("--projection-drain-timeout-ms", 60000),
  projectionDrainPollMs: numberOption("--projection-drain-poll-ms", 500),
};

const summaryOut = config.summaryOut || config.out.replace(/\.json$/i, ".summary.json");
const passthrough = args.filter((arg) =>
  !arg.startsWith("--duration-seconds=")
  && !arg.startsWith("--mode=")
  && !arg.startsWith("--venue-url=")
  && !arg.startsWith("--arena-admin-url=")
  && !arg.startsWith("--compartment=")
  && !arg.startsWith("--out=")
  && !arg.startsWith("--summary-out=")
  && !arg.startsWith("--projection-drain-timeout-ms=")
  && !arg.startsWith("--projection-drain-poll-ms="),
);

const runArgs = [
  "scripts/dev/arena-local-tick-run.mjs",
  `--mode=${config.mode}`,
  `--duration-seconds=${config.durationSeconds}`,
  "--submit-mode=live",
  `--venue-url=${config.venueUrl}`,
  `--arena-admin-url=${config.arenaAdminUrl}`,
  "--seed-reference",
  `--compartment=${config.compartment}`,
  "--command-wait-mode=terminal",
  `--projection-drain-timeout-ms=${config.projectionDrainTimeoutMs}`,
  `--projection-drain-poll-ms=${config.projectionDrainPollMs}`,
  "--require-projection-drain",
  `--out=${config.out}`,
  ...passthrough,
];

const result = spawnSync(process.execPath, runArgs, {
  cwd: new URL("../../", import.meta.url).pathname,
  stdio: "inherit",
});
if (result.status !== 0) {
  process.exit(result.status ?? 1);
}

const report = JSON.parse(readFileSync(config.out, "utf8"));
const summary = hardeningSummary(report);
mkdirSync(dirname(summaryOut), { recursive: true });
writeFileSync(summaryOut, `${JSON.stringify(summary, null, 2)}\n`);

console.log(`arena local hardening summary: ${resolve(summaryOut)}`);
console.log(
  `hardening=${summary.status} duration=${summary.durationSeconds}s commands=${summary.commands.total} timedOut=${summary.commands.timedOut} health=${summary.health.status} houseCommands=${summary.house.submittedCommands}`,
);

if (summary.status !== "pass") {
  process.exitCode = 1;
}

function hardeningSummary(report) {
  const house = report.activityBySchedulingClass?.house_responsive ?? {};
  const commandStatusSummary = report.commandStatusSummary ?? {};
  const healthSummary = report.healthSummary ?? {};
  const rejectSummary = summarizeRejects(report);
  const marketQuality = summarizeMarketQuality(report);
  const latency = summarizeLatency(report);
  const ownOrderCounts = (report.venueReadback?.ownOrders ?? [])
    .filter((entry) => String(entry.botId ?? "").startsWith("builtin-mm"))
    .map((entry) => ({
      botId: entry.botId,
      current: entry.current?.body?.orders?.length ?? 0,
      history: entry.history?.body?.orders?.length ?? 0,
    }));
  const failures = [];
  if (report.status !== "completed") failures.push(`report status ${report.status}`);
  if (commandStatusSummary.timedOut !== 0) failures.push(`timed out commands ${commandStatusSummary.timedOut}`);
  if ((rejectSummary.count ?? 0) > 0) failures.push(`rejected commands ${rejectSummary.count}`);
  if ((commandStatusSummary.byFinalStatus?.COMPLETED ?? 0) + (rejectSummary.count ?? 0) !== commandStatusSummary.commandCount) failures.push("not all commands reached terminal accounting");
  if (healthSummary.status !== "pass") failures.push(`health status ${healthSummary.status}`);
  for (const failure of marketQuality.failures) failures.push(failure);
  if (report.venueReadback?.projectionDrained !== true) failures.push("projection did not drain");
  if ((report.enforcementEvents ?? []).some((event) => event.decision === "freeze")) failures.push("freeze events present");
  if (ownOrderCounts.some((entry) => entry.current === 0)) failures.push("house LP own-order readback empty");

  return {
    schemaVersion: "reef.arena.localHardeningSummary.v0",
    status: failures.length === 0 ? "pass" : "fail",
    failures,
    reportPath: resolve(config.out),
    durationSeconds: report.runPlan?.durationSeconds ?? config.durationSeconds,
    startedAt: report.startedAt,
    completedAt: report.completedAt,
    elapsedMs: report.elapsedMs,
    runPlan: {
      modeId: report.modeId,
      selectedBotCount: report.runPlan?.selectedBotCount,
      instruments: report.runPlan?.instruments,
      schedulingMode: report.runPlan?.schedulingMode,
      totalTickCount: report.runPlan?.totalTickCount,
    },
    commands: {
      total: commandStatusSummary.commandCount ?? 0,
      timedOut: commandStatusSummary.timedOut ?? 0,
      byRoute: commandStatusSummary.byRoute ?? {},
      byFinalStatus: commandStatusSummary.byFinalStatus ?? {},
      rejects: rejectSummary,
      avgIntakeElapsedMs: commandStatusSummary.avgIntakeElapsedMs ?? 0,
      avgStatusElapsedMs: commandStatusSummary.avgStatusElapsedMs ?? 0,
    },
    latency,
    health: {
      status: healthSummary.status ?? "unknown",
      topOfBookPct: healthSummary.topOfBookPct ?? 0,
      depthPct: healthSummary.depthPct ?? 0,
      medianQuotedSpreadBps: healthSummary.medianQuotedSpreadBps ?? null,
      p95QuotedSpreadBps: healthSummary.p95QuotedSpreadBps ?? null,
      crossedBookCount: healthSummary.crossedBookCount ?? 0,
      emptyBookCount: healthSummary.emptyBookCount ?? 0,
    },
    marketQuality,
    house: {
      botCount: house.botCount ?? 0,
      ticks: house.ticks ?? 0,
      submittedCommands: house.submittedCommands ?? 0,
      timedOutCommands: house.timedOutCommands ?? 0,
      dataCalls: house.dataCalls ?? 0,
      ownOrderCounts,
    },
    activityBySchedulingClass: report.activityBySchedulingClass ?? {},
  };
}

function summarizeLatency(report) {
  const overall = createLatencyBucket();
  const bySchedulingClass = {};
  const byRole = {};

  for (const session of report.sessionReports ?? []) {
    const schedulingClass = session.schedulingClass ?? session.bot?.schedulingClass ?? "unknown";
    const role = session.bot?.role ?? "unknown";
    const classBucket = ensureLatencyBucket(bySchedulingClass, schedulingClass);
    const roleBucket = ensureLatencyBucket(byRole, role);

    for (const tick of session.ticks ?? []) {
      recordTickLatency(overall, tick);
      recordTickLatency(classBucket, tick);
      recordTickLatency(roleBucket, tick);

      for (const command of tick.submission?.commands ?? []) {
        recordCommandLatency(overall, command);
        recordCommandLatency(classBucket, command);
        recordCommandLatency(roleBucket, command);
      }
    }
  }

  return {
    overall: finalizeLatencyBucket(overall),
    bySchedulingClass: finalizeLatencyBuckets(bySchedulingClass),
    byRole: finalizeLatencyBuckets(byRole),
  };
}

function createLatencyBucket() {
  return {
    ticks: 0,
    failedTicks: 0,
    commands: 0,
    completedCommands: 0,
    rejectedCommands: 0,
    timedOutCommands: 0,
    tickElapsedMs: [],
    commandElapsedMs: [],
    intakeElapsedMs: [],
    statusElapsedMs: [],
    firstStatusElapsedMs: [],
    statusPollCount: [],
  };
}

function ensureLatencyBucket(collection, key) {
  const normalized = String(key ?? "unknown");
  if (!collection[normalized]) collection[normalized] = createLatencyBucket();
  return collection[normalized];
}

function recordTickLatency(bucket, tick) {
  bucket.ticks += 1;
  if (tick.ok === false) bucket.failedTicks += 1;
  pushFinite(bucket.tickElapsedMs, tick.elapsedMs);
}

function recordCommandLatency(bucket, command) {
  bucket.commands += 1;
  if (command.finalStatus === "COMPLETED") bucket.completedCommands += 1;
  if (command.finalStatus === "REJECTED" || command.rejected === true) bucket.rejectedCommands += 1;
  if (command.timedOut === true) bucket.timedOutCommands += 1;
  pushFinite(bucket.commandElapsedMs, command.elapsedMs);
  pushFinite(bucket.intakeElapsedMs, command.intakeElapsedMs);
  pushFinite(bucket.statusElapsedMs, command.statusElapsedMs);
  pushFinite(bucket.firstStatusElapsedMs, command.firstStatusElapsedMs);
  pushFinite(bucket.statusPollCount, command.statusPollCount);
}

function finalizeLatencyBuckets(collection) {
  return Object.fromEntries(
    Object.entries(collection)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, bucket]) => [key, finalizeLatencyBucket(bucket)]),
  );
}

function finalizeLatencyBucket(bucket) {
  return {
    ticks: bucket.ticks,
    failedTicks: bucket.failedTicks,
    commands: bucket.commands,
    completedCommands: bucket.completedCommands,
    rejectedCommands: bucket.rejectedCommands,
    timedOutCommands: bucket.timedOutCommands,
    tickElapsedMs: distribution(bucket.tickElapsedMs),
    commandElapsedMs: distribution(bucket.commandElapsedMs),
    intakeElapsedMs: distribution(bucket.intakeElapsedMs),
    statusElapsedMs: distribution(bucket.statusElapsedMs),
    firstStatusElapsedMs: distribution(bucket.firstStatusElapsedMs),
    statusPollCount: distribution(bucket.statusPollCount),
  };
}

function summarizeMarketQuality(report) {
  const healthTargets = report.mode?.healthTargets ?? {};
  const minTopOfBookPct = Number(healthTargets.minTopOfBookPct ?? report.healthSummary?.thresholds?.minTopOfBookPct ?? 90);
  const minDepthPct = Number(healthTargets.minDepthPct ?? report.healthSummary?.thresholds?.minDepthPct ?? 90);
  const maxP95QuotedSpreadBps = Number(healthTargets.maxP95QuotedSpreadBps ?? report.healthSummary?.thresholds?.maxP95QuotedSpreadBps ?? 50);
  const instruments = new Map();
  const ensureInstrument = (instrumentId) => {
    const key = String(instrumentId ?? "");
    if (key.length === 0) return undefined;
    if (!instruments.has(key)) {
      instruments.set(key, {
        instrumentId: key,
        samples: 0,
        topOfBookSamples: 0,
        depthSamples: 0,
        emptyBookCount: 0,
        lockedBookCount: 0,
        crossedBookCount: 0,
        spreadBps: [],
        submittedCommands: 0,
        completedCommands: 0,
        rejectedCommands: 0,
        timedOutCommands: 0,
        filledCommands: 0,
        tradeCount: 0,
        tradedQuantity: 0,
        notionalNanos: 0,
        commandCountByClass: {},
        filledCommandCountByClass: {},
        tradeCountByClass: {},
        sides: {},
      });
    }
    return instruments.get(key);
  };

  for (const sample of report.healthSamples ?? []) {
    for (const snapshot of sample.snapshots ?? []) {
      const instrument = ensureInstrument(snapshot.instrumentId);
      if (instrument === undefined) continue;
      instrument.samples += 1;
      if (snapshot.topOfBook) instrument.topOfBookSamples += 1;
      if (snapshot.bidDepthAvailable && snapshot.askDepthAvailable) instrument.depthSamples += 1;
      if (snapshot.emptyBook) instrument.emptyBookCount += 1;
      if (snapshot.lockedBook) instrument.lockedBookCount += 1;
      if (snapshot.crossedBook) instrument.crossedBookCount += 1;
      if (Number.isFinite(Number(snapshot.quotedSpreadBps))) {
        instrument.spreadBps.push(Number(snapshot.quotedSpreadBps));
      }
    }
  }

  for (const session of report.sessionReports ?? []) {
    const schedulingClass = session.schedulingClass ?? session.bot?.schedulingClass ?? "unknown";
    for (const tick of session.ticks ?? []) {
      for (const command of tick.submission?.commands ?? []) {
        const instrumentId = command.statusBody?.instrumentId ?? command.body?.instrumentId ?? command.intakeBody?.accepted?.instrumentId ?? command.commandBody?.instrumentId ?? command.routeInstrumentId ?? command.request?.instrumentId ?? command.venueCommand?.body?.instrumentId;
        const commandInstrumentId = instrumentId ?? findCommandInstrument(tick, command.commandId);
        const instrument = ensureInstrument(commandInstrumentId);
        if (instrument === undefined) continue;
        const side = command.body?.side ??
          command.commandBody?.side ??
          command.venueCommand?.body?.side ??
          findCommandSide(tick, command.commandId) ??
          "UNKNOWN";
        const sideStats = ensureSideStats(instrument, side);
        increment(instrument.commandCountByClass, schedulingClass);
        instrument.submittedCommands += 1;
        sideStats.submittedCommands += 1;
        if (command.finalStatus === "COMPLETED") instrument.completedCommands += 1;
        if (command.finalStatus === "COMPLETED") sideStats.completedCommands += 1;
        if (command.finalStatus === "REJECTED" || command.rejected) {
          instrument.rejectedCommands += 1;
          sideStats.rejectedCommands += 1;
        }
        if (command.timedOut) {
          instrument.timedOutCommands += 1;
          sideStats.timedOutCommands += 1;
        }
        const trades = Array.isArray(command.intakeBody?.trades) ? command.intakeBody.trades : [];
        const executions = Array.isArray(command.intakeBody?.executions) ? command.intakeBody.executions : [];
        if (trades.length > 0 || executions.length > 0) {
          instrument.filledCommands += 1;
          sideStats.filledCommands += 1;
          increment(instrument.filledCommandCountByClass, schedulingClass);
        }
        for (const trade of trades) {
          const tradeInstrument = ensureInstrument(trade.instrumentId ?? commandInstrumentId);
          if (tradeInstrument === undefined) continue;
          const quantity = numberValue(trade.quantityUnits);
          const price = numberValue(trade.price);
          tradeInstrument.tradeCount += 1;
          tradeInstrument.tradedQuantity += quantity;
          tradeInstrument.notionalNanos += quantity * price;
          if (tradeInstrument === instrument) {
            sideStats.tradeCount += 1;
            sideStats.tradedQuantity += quantity;
          }
          increment(tradeInstrument.tradeCountByClass, schedulingClass);
        }
      }
    }
  }

  const byInstrument = Array.from(instruments.values())
    .sort((left, right) => left.instrumentId.localeCompare(right.instrumentId))
    .map((instrument) => {
      const spreadValues = instrument.spreadBps.slice().sort((left, right) => left - right);
      return {
        instrumentId: instrument.instrumentId,
        samples: instrument.samples,
        topOfBookPct: pct(instrument.topOfBookSamples, instrument.samples),
        depthPct: pct(instrument.depthSamples, instrument.samples),
        emptyBookCount: instrument.emptyBookCount,
        lockedBookCount: instrument.lockedBookCount,
        crossedBookCount: instrument.crossedBookCount,
        medianQuotedSpreadBps: percentile(spreadValues, 0.5),
        p95QuotedSpreadBps: percentile(spreadValues, 0.95),
        submittedCommands: instrument.submittedCommands,
        completedCommands: instrument.completedCommands,
        rejectedCommands: instrument.rejectedCommands,
        timedOutCommands: instrument.timedOutCommands,
        filledCommands: instrument.filledCommands,
        fillRatePct: pct(instrument.filledCommands, instrument.submittedCommands),
        tradeCount: instrument.tradeCount,
        tradedQuantity: instrument.tradedQuantity,
        notional: instrument.notionalNanos / 1_000_000_000,
        commandCountByClass: instrument.commandCountByClass,
        filledCommandCountByClass: instrument.filledCommandCountByClass,
        tradeCountByClass: instrument.tradeCountByClass,
        bySide: Object.fromEntries(
          Object.entries(instrument.sides)
            .sort(([left], [right]) => left.localeCompare(right))
            .map(([side, stats]) => [
              side,
              {
                ...stats,
                fillRatePct: pct(stats.filledCommands, stats.submittedCommands),
              },
            ]),
        ),
      };
    });

  const failures = [];
  for (const instrument of byInstrument) {
    if (instrument.samples === 0) failures.push(`market ${instrument.instrumentId} has no health samples`);
    if (instrument.topOfBookPct < minTopOfBookPct) failures.push(`market ${instrument.instrumentId} topOfBookPct ${instrument.topOfBookPct.toFixed(2)} < ${minTopOfBookPct}`);
    if (instrument.depthPct < minDepthPct) failures.push(`market ${instrument.instrumentId} depthPct ${instrument.depthPct.toFixed(2)} < ${minDepthPct}`);
    if (instrument.p95QuotedSpreadBps !== null && instrument.p95QuotedSpreadBps > maxP95QuotedSpreadBps) {
      failures.push(`market ${instrument.instrumentId} p95SpreadBps ${instrument.p95QuotedSpreadBps.toFixed(2)} > ${maxP95QuotedSpreadBps}`);
    }
    if (instrument.tradeCount === 0) failures.push(`market ${instrument.instrumentId} has no trades`);
    for (const [side, stats] of Object.entries(instrument.bySide)) {
      if (stats.submittedCommands > 0 && stats.filledCommands === 0) {
        failures.push(`market ${instrument.instrumentId} side ${side} has no filled commands`);
      }
    }
  }

  return {
    status: failures.length === 0 ? "pass" : "fail",
    failures,
    thresholds: {
      minTopOfBookPct,
      minDepthPct,
      maxP95QuotedSpreadBps,
      minTradesPerInstrument: 1,
    },
    byInstrument,
    totals: {
      instruments: byInstrument.length,
      tradeCount: byInstrument.reduce((total, instrument) => total + instrument.tradeCount, 0),
      tradedQuantity: byInstrument.reduce((total, instrument) => total + instrument.tradedQuantity, 0),
      submittedCommands: byInstrument.reduce((total, instrument) => total + instrument.submittedCommands, 0),
      filledCommands: byInstrument.reduce((total, instrument) => total + instrument.filledCommands, 0),
      fillRatePct: pct(
        byInstrument.reduce((total, instrument) => total + instrument.filledCommands, 0),
        byInstrument.reduce((total, instrument) => total + instrument.submittedCommands, 0),
      ),
    },
  };
}

function findCommandInstrument(tick, commandId) {
  const venueCommand = (tick.venueCommands ?? []).find((candidate) => candidate.body?.commandId === commandId);
  return venueCommand?.body?.instrumentId;
}

function findCommandSide(tick, commandId) {
  const venueCommand = (tick.venueCommands ?? []).find((candidate) => candidate.body?.commandId === commandId);
  return venueCommand?.body?.side;
}

function ensureSideStats(instrument, side) {
  const key = String(side ?? "UNKNOWN");
  if (!instrument.sides[key]) {
    instrument.sides[key] = {
      submittedCommands: 0,
      completedCommands: 0,
      rejectedCommands: 0,
      timedOutCommands: 0,
      filledCommands: 0,
      tradeCount: 0,
      tradedQuantity: 0,
    };
  }
  return instrument.sides[key];
}

function summarizeRejects(report) {
  const commands = (report.sessionReports ?? [])
    .flatMap((session) => session.ticks ?? [])
    .flatMap((tick) => tick.submission?.commands ?? [])
    .filter((command) => command.finalStatus === "REJECTED" || command.rejected === true);
  const byCode = {};
  const byBotId = {};
  for (const command of commands) {
    const payload = safeJson(command.statusBody?.responsePayloadJson);
    const code = payload?.rejected?.code ?? command.statusBody?.resultStatus ?? "unknown";
    byCode[code] = Number(byCode[code] ?? 0) + 1;
    const botId = botIdFromCommandId(command.commandId);
    byBotId[botId] = Number(byBotId[botId] ?? 0) + 1;
  }
  return { count: commands.length, byCode, byBotId };
}

function safeJson(value) {
  if (typeof value !== "string" || value.length === 0) return {};
  try {
    return JSON.parse(value);
  } catch {
    return {};
  }
}

function botIdFromCommandId(commandId) {
  const value = String(commandId ?? "");
  const match = value.match(/^equity-multi-local-(.+)-\d+-cmd-/);
  return match?.[1] ?? "unknown";
}

function increment(counter, key) {
  counter[key] = Number(counter[key] ?? 0) + 1;
}

function pushFinite(values, value) {
  const number = Number(value);
  if (Number.isFinite(number)) values.push(number);
}

function distribution(values) {
  const sorted = values.slice().sort((left, right) => left - right);
  const total = sorted.reduce((sum, value) => sum + value, 0);
  return {
    count: sorted.length,
    avg: sorted.length === 0 ? null : total / sorted.length,
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    p99: percentile(sorted, 0.99),
    max: sorted.length === 0 ? null : sorted[sorted.length - 1],
  };
}

function pct(numerator, denominator) {
  return denominator === 0 ? 0 : (numerator / denominator) * 100;
}

function percentile(sortedValues, percentileValue) {
  if (sortedValues.length === 0) return null;
  const index = Math.min(sortedValues.length - 1, Math.max(0, Math.ceil(sortedValues.length * percentileValue) - 1));
  return sortedValues[index];
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function stringOption(name, fallback) {
  const prefix = `${name}=`;
  const found = args.find((arg) => arg.startsWith(prefix));
  return found === undefined ? fallback : found.slice(prefix.length);
}

function numberOption(name, fallback) {
  const value = Number(stringOption(name, ""));
  return Number.isFinite(value) && value > 0 ? value : fallback;
}
