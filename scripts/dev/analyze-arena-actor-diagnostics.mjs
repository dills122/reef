import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_PROFILE_CATALOG = "packages/scenario-definitions/arena/actor-profiles.v1.json";

const KNOB_METRIC_MAP = {
  aggression: {
    expectedEffect: "Higher values should generally increase participation, spread crossing, fill pressure, and inventory/PnL variance.",
    metrics: ["submittedCommands", "submittedLimitOrders", "grossSubmittedNotional", "fillCount", "fillRatio", "grossExecutedNotional", "pnlPerExecutedNotionalBps", "totalPnl", "marketInteractionScore", "inventoryExposureRatio"],
  },
  cancelDiscipline: {
    expectedEffect: "Lower discipline should increase cancel/replace pressure, invalid intent risk, and venue stress.",
    metrics: ["cancelCommands", "cancelReplaceRatio", "invalidIntentRate", "maxVenueCommandsPerTick"],
  },
  inventorySkew: {
    expectedEffect: "Higher skew should alter terminal inventory pressure and asymmetric quote participation.",
    metrics: ["inventoryGrossNotional", "inventoryExposureRatio", "totalPnl", "riskScore"],
  },
  latencyJitter: {
    expectedEffect: "Higher jitter should reduce completion rate and increase timeout/operational risk.",
    metrics: ["completionRate", "timeoutRate", "timedOutCommands", "conductScore"],
  },
  maxSpreadCrossBps: {
    expectedEffect: "Higher values should increase taker aggressiveness, fill chance, and adverse-selection exposure.",
    metrics: ["fillCount", "fillRatio", "grossExecutedNotional", "pnlPerExecutedNotionalBps", "totalPnl"],
  },
  orderRate: {
    expectedEffect: "Higher rates should increase commands, submitted notional, market interaction score, and burst pressure.",
    metrics: ["submittedCommands", "submittedLimitOrders", "grossSubmittedNotional", "fillCount", "fillRatio", "grossExecutedNotional", "maxVenueCommandsPerTick", "marketInteractionScore"],
  },
  panicThreshold: {
    expectedEffect: "Lower thresholds should increase bursty taker behavior during stress regimes.",
    metrics: ["submittedCommands", "maxVenueCommandsPerTick", "fillCount", "riskScore"],
  },
  quoteSize: {
    expectedEffect: "Higher quote size should increase displayed depth proxy, submitted quantity, and inventory/fill exposure.",
    metrics: ["grossSubmittedQuantity", "grossSubmittedNotional", "fillCount", "inventoryGrossNotional"],
  },
  quoteSpreadBps: {
    expectedEffect: "Lower spread should tighten venue quote quality but may increase fills and adverse-selection exposure.",
    metrics: ["providerMedianQuotedSpreadBps", "providerP95QuotedSpreadBps", "medianQuotedSpreadBps", "p95QuotedSpreadBps", "fillCount", "totalPnl"],
  },
  riskDiscipline: {
    expectedEffect: "Lower discipline should increase rejects, freezes, operational pauses, and inventory/risk penalties.",
    metrics: ["rejectedCommands", "failedCommands", "freezeCount", "operationalPauseCount", "riskScore", "conductScore"],
  },
};

const CORE_METRICS = Array.from(new Set(Object.values(KNOB_METRIC_MAP).flatMap((entry) => entry.metrics))).sort();

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const options = parseArgs(process.argv.slice(2));
  const report = analyzeArenaActorDiagnostics(options);
  if (options.out) {
    mkdirSync(dirname(resolve(options.out)), { recursive: true });
    writeJson(options.out, report);
    console.log(JSON.stringify(actorDiagnosticsCliSummary(report, options.out), null, 2));
  } else {
    console.log(JSON.stringify(report, null, 2));
  }
}

export function analyzeArenaActorDiagnostics(options = {}) {
  const reports = loadReports(options);
  const catalog = readJson(resolve(options.actorProfileCatalog ?? DEFAULT_PROFILE_CATALOG));
  const catalogProfiles = new Map((catalog.profiles ?? []).map((profile) => [profile.profileId, profile]));
  const observations = [];
  for (const report of reports) {
    for (const bot of report.botResults ?? []) {
      observations.push(observationFromBot(report, bot, catalogProfiles));
    }
  }
  const profileDiagnostics = summarizeProfiles(observations);
  const knobDiagnostics = summarizeKnobs(observations);
  return {
    schemaVersion: "reef.arena.actorDiagnostics.v1",
    generatedAt: new Date().toISOString(),
    source: sourceSummary(options, reports),
    actorProfileCatalog: {
      catalogId: catalog.catalogId ?? "",
      version: catalog.version ?? "",
      profileCount: catalog.profiles?.length ?? 0,
    },
    coverage: {
      reportCount: reports.length,
      observationCount: observations.length,
      profileCount: profileDiagnostics.length,
      actorClassCounts: sortedCounts(observations.map((entry) => entry.actorClass)),
      scoreEffectCounts: sortedCounts(observations.map((entry) => entry.scoreEffect)),
    },
    knobDictionary: knobDictionary(),
    profileDiagnostics,
    knobDiagnostics,
    caveats: diagnosticsCaveats(observations, knobDiagnostics),
  };
}

export function actorDiagnosticsCliSummary(report, outPath = "") {
  return {
    schemaVersion: "reef.arena.actorDiagnosticsCliSummary.v1",
    outPath,
    coverage: report.coverage,
    profiles: (report.profileDiagnostics ?? []).map((profile) => ({
      profileId: profile.profileId,
      actorClass: profile.actorClass,
      scoreEffect: profile.scoreEffect,
      observationCount: profile.observationCount,
      submittedCommandsAvg: profile.metrics?.submittedCommands?.avg ?? null,
      fillCountAvg: profile.metrics?.fillCount?.avg ?? null,
      fillRatioAvg: profile.metrics?.fillRatio?.avg ?? null,
      totalPnlAvg: profile.metrics?.totalPnl?.avg ?? null,
      marketInteractionScoreAvg: profile.metrics?.marketInteractionScore?.avg ?? null,
      medianQuotedSpreadBpsAvg: profile.metrics?.medianQuotedSpreadBps?.avg ?? null,
      providerMedianQuotedSpreadBpsAvg: profile.metrics?.providerMedianQuotedSpreadBps?.avg ?? null,
      instrumentationGaps: profile.instrumentationGaps ?? [],
    })),
    knobs: (report.knobDiagnostics ?? []).map((knob) => ({
      knob: knob.knob,
      observedValueCount: knob.observedValueCount,
      inferenceQuality: knob.inferenceQuality,
      values: (knob.values ?? []).map((value) => ({
        value: value.value,
        observationCount: value.observationCount,
        profileIds: value.profileIds,
      })),
      byProfile: (knob.byProfile ?? []).map((profile) => ({
        profileId: profile.profileId,
        actorClass: profile.actorClass,
        observedValueCount: profile.observedValueCount,
        inferenceQuality: profile.inferenceQuality,
        values: (profile.values ?? []).map((value) => ({
          value: value.value,
          observationCount: value.observationCount,
        })),
      })),
    })),
    caveats: report.caveats ?? [],
  };
}

function loadReports(options) {
  const paths = [];
  if (options.manifest) {
    const manifestPath = resolve(options.manifest);
    const manifest = readJson(manifestPath);
    const manifestDir = dirname(manifestPath);
    for (const entry of manifest.entries ?? []) {
      if (entry.status === "completed" && entry.reportPath) paths.push(resolveMaybeRelative(entry.reportPath, manifestDir));
    }
  }
  for (const reportPath of options.reports ?? []) {
    paths.push(reportPath);
  }
  const uniquePaths = Array.from(new Set(paths));
  if (uniquePaths.length === 0) {
    throw new Error("provide --manifest=PATH or one or more --report=PATH arguments");
  }
  return uniquePaths.map((path) => readJson(resolve(path)));
}

function resolveMaybeRelative(path, baseDir) {
  return isAbsolute(path) ? path : resolve(baseDir, path);
}

function observationFromBot(report, bot, catalogProfiles) {
  const profile = bot.actorProfile ?? {};
  const catalogProfile = catalogProfiles.get(profile.profileId) ?? {};
  const scoreDiagnostics = bot.scoreBreakdown?.diagnostics ?? {};
  const components = bot.scoreBreakdown?.components ?? {};
  const liquidity = bot.liquidityDiagnostics ?? {};
  const trading = bot.tradingMetrics ?? {};
  const commands = trading.commands ?? {};
  const orderFlow = trading.orderFlow ?? {};
  const executions = trading.executions ?? {};
  const pnl = trading.pnl ?? {};
  const inventory = trading.inventory ?? {};
  const conduct = bot.conductMetrics ?? {};
  const metrics = {
    submittedCommands: numberValue(commands.submitted),
    completedCommands: numberValue(commands.completed),
    failedCommands: numberValue(commands.failed),
    rejectedCommands: numberValue(commands.rejected),
    timedOutCommands: numberValue(commands.timedOut),
    submittedLimitOrders: numberValue(orderFlow.submittedLimitOrders),
    cancelCommands: numberValue(orderFlow.cancelCommands),
    modifyCommands: numberValue(orderFlow.modifyCommands),
    grossSubmittedQuantity: numberValue(orderFlow.grossSubmittedQuantity),
    grossSubmittedNotional: numberValue(orderFlow.grossSubmittedNotional),
    fillCount: numberValue(executions.fillCount ?? scoreDiagnostics.fillCount),
    filledQuantity: numberValue(executions.filledQuantity ?? scoreDiagnostics.filledQuantity),
    grossExecutedNotional: numberValue(executions.grossNotional ?? scoreDiagnostics.grossExecutedNotional),
    fillRatio: nullableNumber(scoreDiagnostics.fillRatio),
    completionRate: nullableNumber(scoreDiagnostics.completionRate),
    totalPnl: nullableNumber(pnl.total ?? scoreDiagnostics.totalPnl),
    pnlPerExecutedNotionalBps: nullableNumber(scoreDiagnostics.pnlPerExecutedNotionalBps),
    inventoryGrossNotional: nullableNumber(inventory.grossNotional ?? scoreDiagnostics.inventoryGrossNotional),
    inventoryExposureRatio: nullableNumber(scoreDiagnostics.inventoryExposureRatio),
    cancelReplaceRatio: nullableNumber(conduct.cancelReplaceRatio ?? scoreDiagnostics.cancelReplaceRatio),
    invalidIntentRate: nullableNumber(conduct.invalidIntentRate ?? scoreDiagnostics.invalidIntentRate),
    timeoutRate: nullableNumber(conduct.timeoutRate ?? scoreDiagnostics.timeoutRate),
    maxVenueCommandsPerTick: numberValue(conduct.maxVenueCommandsPerTick ?? scoreDiagnostics.maxVenueCommandsPerTick),
    freezeCount: numberValue(bot.freezeCount ?? scoreDiagnostics.freezeCount),
    operationalPauseCount: numberValue(bot.operationalPauseCount ?? scoreDiagnostics.operationalPauseCount),
    marketInteractionScore: nullableNumber(components.marketInteraction),
    riskScore: nullableNumber(components.risk),
    conductScore: nullableNumber(components.conduct),
    shadowScore: nullableNumber(bot.scoreBreakdown?.shadowScore),
    medianQuotedSpreadBps: nullableNumber(liquidity.quoteQuality?.medianQuotedSpreadBps),
    p95QuotedSpreadBps: nullableNumber(liquidity.quoteQuality?.p95QuotedSpreadBps),
    providerMedianQuotedSpreadBps: nullableNumber(liquidity.providerQuoteQuality?.medianQuotedSpreadBps),
    providerP95QuotedSpreadBps: nullableNumber(liquidity.providerQuoteQuality?.p95QuotedSpreadBps),
  };
  return {
    runId: report.runId ?? "",
    modeId: report.mode?.modeId ?? "",
    botId: bot.botId ?? "",
    actorClass: bot.actorClass ?? profile.actorClass ?? catalogProfile.actorClass ?? "unknown",
    profileId: profile.profileId ?? catalogProfile.profileId ?? "unknown",
    profileVersion: profile.profileVersion ?? catalogProfile.version ?? "",
    difficultyBucket: profile.difficultyBucket ?? catalogProfile.difficultyBucket ?? "",
    scoreEffect: profile.scoreEffect ?? catalogProfile.scoreEffect ?? "",
    params: profile.params ?? catalogProfile.params ?? {},
    scoreEligible: bot.scoreBreakdown?.scoreEligible === true,
    scoreNeutral: bot.liquidityDiagnostics?.scoreNeutral === true || bot.scoreBreakdown?.scoreEligible !== true,
    flags: [
      ...(bot.scoreBreakdown?.diagnostics?.flags ?? []),
      ...(bot.liquidityDiagnostics?.flags ?? []),
    ],
    metrics,
  };
}

function summarizeProfiles(observations) {
  const groups = groupBy(observations, (entry) => entry.profileId);
  return Array.from(groups.entries())
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([profileId, entries]) => ({
      profileId,
      actorClass: entries[0]?.actorClass ?? "",
      difficultyBucket: entries[0]?.difficultyBucket ?? "",
      scoreEffect: entries[0]?.scoreEffect ?? "",
      params: entries[0]?.params ?? {},
      observationCount: entries.length,
      botCount: new Set(entries.map((entry) => entry.botId)).size,
      runCount: new Set(entries.map((entry) => entry.runId)).size,
      scoreNeutral: entries.every((entry) => entry.scoreNeutral),
      metrics: metricStats(entries, CORE_METRICS),
      flags: sortedCounts(entries.flatMap((entry) => entry.flags)),
      instrumentationGaps: profileInstrumentationGaps(entries),
    }));
}

function summarizeKnobs(observations) {
  return Object.entries(KNOB_METRIC_MAP)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([knob, spec]) => {
      const withKnob = observations.filter((entry) => Object.hasOwn(entry.params ?? {}, knob));
      const valueGroups = groupBy(withKnob, (entry) => String(entry.params[knob]));
      const values = Array.from(valueGroups.entries())
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([value, entries]) => ({
          value,
          observationCount: entries.length,
          profileIds: Array.from(new Set(entries.map((entry) => entry.profileId))).sort(),
          actorClasses: sortedCounts(entries.map((entry) => entry.actorClass)),
          metrics: metricStats(entries, spec.metrics),
        }));
      return {
        knob,
        expectedEffect: spec.expectedEffect,
        metricsToWatch: spec.metrics,
        observedValueCount: values.length,
        values,
        byProfile: summarizeKnobByProfile(withKnob, knob, spec.metrics),
        inferenceQuality: values.length < 2 ? "insufficient-variation" : "directional-diagnostic-only",
      };
    });
}

function summarizeKnobByProfile(observations, knob, metrics) {
  return Array.from(groupBy(observations, (entry) => entry.profileId).entries())
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([profileId, profileEntries]) => {
      const valueGroups = groupBy(profileEntries, (entry) => String(entry.params[knob]));
      return {
        profileId,
        actorClass: profileEntries[0]?.actorClass ?? "",
        scoreEffect: profileEntries[0]?.scoreEffect ?? "",
        observedValueCount: valueGroups.size,
        values: Array.from(valueGroups.entries())
          .sort(([left], [right]) => left.localeCompare(right))
          .map(([value, entries]) => ({
            value,
            observationCount: entries.length,
            metrics: metricStats(entries, metrics),
          })),
        inferenceQuality: valueGroups.size < 2 ? "insufficient-variation" : "directional-diagnostic-only",
      };
    });
}

function metricStats(entries, metricNames) {
  const stats = {};
  for (const metricName of metricNames) {
    stats[metricName] = numericStats(entries.map((entry) => entry.metrics?.[metricName]));
  }
  return stats;
}

function profileInstrumentationGaps(entries) {
  const gaps = [];
  if (entries.every((entry) => numberValue(entry.metrics.fillCount) === 0)) gaps.push("no-fills-observed");
  if (entries.every((entry) => nullableNumber(entry.metrics.totalPnl) === null)) gaps.push("no-pnl-observed");
  if (entries.every((entry) => nullableNumber(entry.metrics.pnlPerExecutedNotionalBps) === null)) gaps.push("no-pnl-per-executed-notional");
  if (entries.some((entry) => entry.actorClass === "house_market_maker") && entries.every((entry) => nullableNumber(entry.metrics.medianQuotedSpreadBps) === null)) {
    gaps.push("no-liquidity-quote-quality");
  }
  return gaps;
}

function diagnosticsCaveats(observations, knobDiagnostics) {
  const caveats = [];
  if (observations.length === 0) caveats.push("no-observations");
  if (new Set(observations.map((entry) => entry.runId)).size < 3) caveats.push("low-run-count");
  if (observations.every((entry) => numberValue(entry.metrics.fillCount) === 0)) caveats.push("no-fills-observed");
  if (knobDiagnostics.some((entry) => entry.inferenceQuality === "insufficient-variation")) caveats.push("some-knobs-have-insufficient-variation");
  caveats.push("diagnostics are observational; compare matched scenario matrices before changing scoring weights");
  return caveats;
}

function knobDictionary() {
  return Object.fromEntries(Object.entries(KNOB_METRIC_MAP).map(([knob, spec]) => [
    knob,
    {
      expectedEffect: spec.expectedEffect,
      metricsToWatch: spec.metrics,
    },
  ]));
}

function sourceSummary(options, reports) {
  return {
    manifestPath: options.manifest ?? "",
    reportPaths: options.reports ?? [],
    runIds: reports.map((report) => report.runId ?? ""),
    modeIds: Array.from(new Set(reports.map((report) => report.mode?.modeId ?? ""))).filter((value) => value.length > 0).sort(),
  };
}

export function parseArgs(argv) {
  const options = { reports: [] };
  for (const arg of argv) {
    if (arg.startsWith("--manifest=")) options.manifest = arg.slice("--manifest=".length);
    else if (arg.startsWith("--report=")) options.reports.push(arg.slice("--report=".length));
    else if (arg.startsWith("--actor-profile-catalog=")) options.actorProfileCatalog = arg.slice("--actor-profile-catalog=".length);
    else if (arg.startsWith("--out=")) options.out = arg.slice("--out=".length);
  }
  return options;
}

function groupBy(values, keyFn) {
  const groups = new Map();
  for (const value of values) {
    const key = keyFn(value);
    const entries = groups.get(key) ?? [];
    entries.push(value);
    groups.set(key, entries);
  }
  return groups;
}

function sortedCounts(values) {
  const counts = {};
  for (const value of values) {
    const key = String(value ?? "unknown");
    counts[key] = (counts[key] ?? 0) + 1;
  }
  return Object.fromEntries(Object.entries(counts).sort(([left], [right]) => left.localeCompare(right)));
}

function numericStats(values) {
  const numbers = values
    .map((value) => nullableNumber(value))
    .filter((value) => value !== null)
    .sort((left, right) => left - right);
  if (numbers.length === 0) return { count: 0, min: null, max: null, avg: null };
  return {
    count: numbers.length,
    min: numbers[0],
    max: numbers[numbers.length - 1],
    avg: Number((numbers.reduce((total, value) => total + value, 0) / numbers.length).toFixed(6)),
  };
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function nullableNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}
