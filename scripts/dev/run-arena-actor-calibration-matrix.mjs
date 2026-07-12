import { spawnSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  actorDiagnosticsCliSummary,
  analyzeArenaActorDiagnostics,
} from "./analyze-arena-actor-diagnostics.mjs";

const DEFAULT_MODE = "packages/scenario-definitions/arena/equity-multi-local.v1.json";
const DEFAULT_OUT_DIR = "artifacts/arena-actor-calibration";

const CALIBRATION_ENVIRONMENTS = {
  default: {
    id: "default",
    description: "Use source mode and bot catalog without environment-level actor changes.",
    modePatch: {},
    actorProfileParamOverrides: {},
  },
  "thin-wide-liquidity": {
    id: "thin-wide-liquidity",
    description: "Thin, wide house liquidity for NPC taker sensitivity checks.",
    modePatch: {
      houseLiquidityDefaults: {
        targetSpreadBps: 100,
        quoteSize: 2,
      },
      healthTargets: {
        minDepthPct: 70,
        maxMedianQuotedSpreadBps: 125,
        maxP95QuotedSpreadBps: 175,
      },
      botRefs: [
        "builtin-mm-lifecycle-safe",
        "builtin-mm-msft-lifecycle",
        "builtin-mm-nvda-lifecycle",
        "builtin-mm-tsla-lifecycle",
        "builtin-mm-amzn-lifecycle",
        "builtin-mm-refreshing",
        "builtin-npc-momentum",
        "builtin-npc-taker-aapl",
        "builtin-npc-taker-msft",
        "builtin-npc-taker-nvda",
        "builtin-npc-taker-tsla",
        "builtin-npc-taker-amzn",
      ],
    },
    actorProfileParamOverrides: {
      "mm-tight-bluechip": {
        quoteSpreadBps: 100,
        quoteSize: 2,
      },
      "npc-bad-aggressive-retail": {
        maxSpreadCrossBps: 75,
      },
    },
  },
};

const DEFAULT_GROUPS = [
  {
    id: "mm-quote-spread",
    description: "Market maker quote width sensitivity.",
    targetProfileId: "mm-tight-bluechip",
    knob: "quoteSpreadBps",
    values: [10, 20, 40],
    metricsToWatch: ["providerMedianQuotedSpreadBps", "medianQuotedSpreadBps", "fillCount", "totalPnl", "adverseSelectionAvgMarkoutBps", "adverseSelectionAdverseFillPct"],
  },
  {
    id: "mm-quote-size",
    description: "Market maker displayed depth and inventory sensitivity.",
    targetProfileId: "mm-tight-bluechip",
    knob: "quoteSize",
    values: [5, 10, 25],
    metricsToWatch: ["grossSubmittedQuantity", "grossSubmittedNotional", "fillCount", "inventoryGrossNotional"],
  },
  {
    id: "npc-aggression",
    description: "NPC taker aggression sensitivity.",
    targetProfileId: "npc-bad-aggressive-retail",
    knob: "aggression",
    values: [0.35, 0.65, 0.95],
    metricsToWatch: ["submittedCommands", "fillCount", "fillRatio", "grossExecutedNotional", "totalPnl"],
  },
  {
    id: "npc-spread-cross",
    description: "NPC taker spread-crossing sensitivity.",
    targetProfileId: "npc-bad-aggressive-retail",
    knob: "maxSpreadCrossBps",
    values: [50, 150, 250],
    metricsToWatch: ["fillCount", "fillRatio", "grossExecutedNotional", "pnlPerExecutedNotionalBps", "totalPnl"],
  },
  {
    id: "npc-order-rate",
    description: "NPC taker order-rate sensitivity.",
    targetProfileId: "npc-bad-aggressive-retail",
    knob: "orderRate",
    values: ["low", "medium", "high"],
    metricsToWatch: ["submittedCommands", "submittedLimitOrders", "grossSubmittedNotional", "fillCount", "fillRatio", "grossExecutedNotional", "maxVenueCommandsPerTick"],
  },
];

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const manifest = runActorCalibrationMatrix(parseArgs(process.argv.slice(2)));
  console.log(JSON.stringify(actorCalibrationCliSummary(manifest), null, 2));
}

export function runActorCalibrationMatrix(options = {}) {
  const outDir = resolve(options.outDir ?? DEFAULT_OUT_DIR);
  const generatedDir = join(outDir, "generated");
  mkdirSync(generatedDir, { recursive: true });

  const modePath = resolve(options.mode ?? DEFAULT_MODE);
  const mode = readJson(modePath);
  const catalogPath = resolve(mode.catalogPath);
  const botCatalog = readJson(catalogPath);
  const actorProfileCatalogPath = resolve(mode.actorProfileCatalogPath ?? "packages/scenario-definitions/arena/actor-profiles.v1.json");
  const groups = selectedGroups(options.groups);
  const environment = selectedEnvironment(options.environment);
  const configured = applyCalibrationEnvironment(mode, botCatalog, environment);
  const entries = buildActorCalibrationEntries({
    mode: configured.mode,
    botCatalog: configured.botCatalog,
    groups,
    includeBaseline: options.includeBaseline !== false,
    generatedDir,
  });

  const reports = [];
  for (const entry of entries) {
    const reportPath = join(outDir, `${entry.id}.json`);
    const result = runArenaEntry(entry, reportPath, options);
    reports.push({
      id: entry.id,
      description: entry.description,
      groupId: entry.groupId,
      targetProfileId: entry.targetProfileId,
      knob: entry.knob,
      value: entry.value,
      modePath: entry.modePath,
      botCatalogPath: entry.botCatalogPath,
      reportPath,
      status: result.status,
      exitCode: result.exitCode,
      error: result.error,
      summary: result.report === null ? null : reportSummary(result.report),
    });
    if (!isCompletedRunStatus(result.status) || result.exitCode !== 0) {
      break;
    }
  }

  const manifestPath = join(outDir, "manifest.json");
  const diagnosticsPath = join(outDir, "actor-diagnostics.json");
  const influenceSummaryPath = join(outDir, "actor-influence-summary.json");
  const completedReports = reports.filter((entry) => isCompletedRunStatus(entry.status) && entry.exitCode === 0);
  const manifest = {
    schemaVersion: "reef.arena.actorCalibrationMatrix.v1",
    generatedAt: new Date().toISOString(),
    runner: "scripts/dev/run-arena-actor-calibration-matrix.mjs",
    outDir,
    sourceModePath: modePath,
    sourceBotCatalogPath: catalogPath,
    actorProfileCatalogPath,
    submitMode: options.submitMode ?? "dry-run",
    reportShape: "compact",
    environment: environmentDescriptor(environment),
    groups: groups.map((group) => ({
      id: group.id,
      description: group.description,
      targetProfileId: group.targetProfileId,
      knob: group.knob,
      values: group.values,
      metricsToWatch: group.metricsToWatch,
    })),
    entries: reports,
    diagnosticsPath: completedReports.length === 0 ? "" : diagnosticsPath,
    influenceSummaryPath: completedReports.length === 0 ? "" : influenceSummaryPath,
    manifestPath,
  };
  writeJson(manifestPath, manifest);
  if (completedReports.length > 0) {
    const diagnostics = analyzeArenaActorDiagnostics({
      manifest: manifestPath,
      actorProfileCatalog: actorProfileCatalogPath,
    });
    writeJson(diagnosticsPath, diagnostics);
    manifest.diagnosticsSummary = actorDiagnosticsCliSummary(diagnostics, diagnosticsPath);
    const influenceSummary = buildActorInfluenceSummary(diagnostics, groups, {
      diagnosticsPath,
      manifestPath,
    });
    writeJson(influenceSummaryPath, influenceSummary);
    manifest.influenceSummary = influenceSummary;
    writeJson(manifestPath, manifest);
  }
  return manifest;
}

export function buildActorInfluenceSummary(diagnostics, groups = DEFAULT_GROUPS, options = {}) {
  return {
    schemaVersion: "reef.arena.actorInfluenceSummary.v1",
    generatedAt: new Date().toISOString(),
    diagnosticsPath: options.diagnosticsPath ?? "",
    manifestPath: options.manifestPath ?? "",
    coverage: diagnostics.coverage ?? {},
    groups: groups.map((group) => summarizeInfluenceGroup(diagnostics, group)),
    caveats: diagnostics.caveats ?? [],
  };
}

export function buildActorCalibrationEntries({ mode, botCatalog, groups = DEFAULT_GROUPS, includeBaseline = true, generatedDir }) {
  const entries = [];
  if (includeBaseline) {
    entries.push(writeCalibrationEntry({
      id: "baseline",
      description: "Unmodified actor profile baseline.",
      mode,
      botCatalog,
      generatedDir,
      groupId: "baseline",
      targetProfileId: "",
      knob: "",
      value: null,
    }));
  }
  for (const group of groups) {
    for (const value of group.values) {
      const id = `${group.id}-${valueToken(value)}`;
      entries.push(writeCalibrationEntry({
        id,
        description: `${group.description} ${group.knob}=${value}`,
        mode,
        botCatalog: catalogWithActorOverride(botCatalog, mode, group.targetProfileId, group.knob, value),
        generatedDir,
        groupId: group.id,
        targetProfileId: group.targetProfileId,
        knob: group.knob,
        value,
      }));
    }
  }
  return entries;
}

export function catalogWithActorOverride(botCatalog, mode, targetProfileId, knob, value) {
  return catalogWithActorParamOverrides(botCatalog, mode, {
    [targetProfileId]: { [knob]: value },
  });
}

export function catalogWithActorParamOverrides(botCatalog, mode, overridesByProfileId = {}) {
  return {
    ...botCatalog,
    catalogId: actorCalibrationCatalogId(botCatalog.catalogId),
    bots: (botCatalog.bots ?? []).map((bot) => {
      const profileId = profileIdForBot(bot, mode);
      const overrides = overridesByProfileId[profileId];
      if (overrides === undefined) {
        return bot;
      }
      return {
        ...bot,
        actorProfileParams: {
          ...(bot.actorProfileParams ?? {}),
          ...overrides,
        },
      };
    }),
  };
}

export function applyCalibrationEnvironment(mode, botCatalog, environment = CALIBRATION_ENVIRONMENTS.default) {
  return {
    mode: deepMerge(mode, environment.modePatch ?? {}),
    botCatalog: catalogWithActorParamOverrides(botCatalog, mode, environment.actorProfileParamOverrides ?? {}),
  };
}

export function parseArgs(argv) {
  const options = { groups: [] };
  for (const arg of argv) {
    if (arg.startsWith("--out-dir=")) options.outDir = arg.slice("--out-dir=".length);
    else if (arg.startsWith("--mode=")) options.mode = arg.slice("--mode=".length);
    else if (arg.startsWith("--environment=")) options.environment = arg.slice("--environment=".length);
    else if (arg.startsWith("--submit-mode=")) options.submitMode = arg.slice("--submit-mode=".length);
    else if (arg.startsWith("--compartment=")) options.compartment = arg.slice("--compartment=".length);
    else if (arg.startsWith("--duration-seconds=")) options.durationSeconds = positiveNumber(arg.slice("--duration-seconds=".length));
    else if (arg.startsWith("--tick-interval-ms=")) options.tickIntervalMs = positiveNumber(arg.slice("--tick-interval-ms=".length));
    else if (arg.startsWith("--venue-url=")) options.venueUrl = arg.slice("--venue-url=".length);
    else if (arg.startsWith("--arena-admin-url=")) options.arenaAdminUrl = arg.slice("--arena-admin-url=".length);
    else if (arg.startsWith("--projection-drain-timeout-ms=")) options.projectionDrainTimeoutMs = positiveNumber(arg.slice("--projection-drain-timeout-ms=".length));
    else if (arg.startsWith("--group=")) options.groups.push(arg.slice("--group=".length));
    else if (arg === "--seed-reference") options.seedReference = true;
    else if (arg === "--require-projection-drain") options.requireProjectionDrain = true;
    else if (arg === "--no-baseline") options.includeBaseline = false;
  }
  if (options.groups.length === 0) delete options.groups;
  return options;
}

export function buildArenaRunArgs(entry, reportPath, options = {}) {
  const args = [
    "scripts/dev/arena-local-tick-run.mjs",
    `--compartment=${options.compartment ?? "vm"}`,
    `--submit-mode=${options.submitMode ?? "dry-run"}`,
    `--mode=${entry.modePath}`,
    "--report-shape=compact",
    `--out=${reportPath}`,
  ];
  if (options.durationSeconds !== undefined) args.push(`--duration-seconds=${options.durationSeconds}`);
  if (options.tickIntervalMs !== undefined) args.push(`--tick-interval-ms=${options.tickIntervalMs}`);
  if (options.venueUrl) args.push(`--venue-url=${options.venueUrl}`);
  if (options.arenaAdminUrl) args.push(`--arena-admin-url=${options.arenaAdminUrl}`);
  if (options.seedReference === true) args.push("--seed-reference");
  if (options.requireProjectionDrain === true) args.push("--require-projection-drain");
  if (options.projectionDrainTimeoutMs !== undefined) args.push(`--projection-drain-timeout-ms=${options.projectionDrainTimeoutMs}`);
  return args;
}

export function actorCalibrationCliSummary(manifest) {
  return {
    schemaVersion: "reef.arena.actorCalibrationCliSummary.v1",
    manifestPath: manifest.manifestPath,
    diagnosticsPath: manifest.diagnosticsPath,
    influenceSummaryPath: manifest.influenceSummaryPath,
    submitMode: manifest.submitMode,
    environment: manifest.environment,
    entryCount: manifest.entries.length,
    completedCount: manifest.entries.filter((entry) => isCompletedRunStatus(entry.status) && entry.exitCode === 0).length,
    failedEntries: manifest.entries.filter((entry) => !isCompletedRunStatus(entry.status) || entry.exitCode !== 0).map((entry) => ({
      id: entry.id,
      status: entry.status,
      exitCode: entry.exitCode,
      error: entry.error,
    })),
    groups: manifest.groups.map((group) => ({
      id: group.id,
      targetProfileId: group.targetProfileId,
      knob: group.knob,
      values: group.values,
    })),
    diagnosticsCaveats: manifest.diagnosticsSummary?.caveats ?? [],
    influence: (manifest.influenceSummary?.groups ?? []).map((group) => ({
      groupId: group.groupId,
      targetProfileId: group.targetProfileId,
      knob: group.knob,
      inferenceQuality: group.inferenceQuality,
      strongestSignals: group.strongestSignals,
      caveats: group.caveats,
    })),
  };
}

function summarizeInfluenceGroup(diagnostics, group) {
  const knobDiagnostics = (diagnostics.knobDiagnostics ?? []).find((entry) => entry.knob === group.knob);
  const profileDiagnostics = knobDiagnostics?.byProfile?.find((entry) => entry.profileId === group.targetProfileId);
  const rows = (group.values ?? []).map((value) => {
    const row = profileDiagnostics?.values?.find((entry) => entry.value === String(value));
    return {
      value,
      observationCount: row?.observationCount ?? 0,
      metrics: metricAverages(row?.metrics ?? {}, group.metricsToWatch ?? []),
    };
  });
  const metricEffects = metricEffectSummary(rows, group.metricsToWatch ?? []);
  const caveats = influenceCaveats(group, rows, profileDiagnostics, metricEffects);
  return {
    groupId: group.id,
    description: group.description,
    targetProfileId: group.targetProfileId,
    knob: group.knob,
    values: group.values,
    metricsToWatch: group.metricsToWatch,
    inferenceQuality: profileDiagnostics?.inferenceQuality ?? "missing-profile-diagnostics",
    rows,
    metricEffects,
    strongestSignals: metricEffects
      .filter((entry) => entry.status === "measured" && entry.delta !== 0)
      .sort((left, right) => Math.abs(right.delta) - Math.abs(left.delta))
      .slice(0, 5),
    caveats,
  };
}

function metricAverages(metricStats, metricsToWatch) {
  return Object.fromEntries((metricsToWatch ?? []).map((metric) => [
    metric,
    metricStats[metric]?.avg ?? null,
  ]));
}

function metricEffectSummary(rows, metricsToWatch) {
  return (metricsToWatch ?? []).map((metric) => {
    const measuredRows = rows.filter((row) => row.metrics[metric] !== null && row.metrics[metric] !== undefined);
    if (measuredRows.length < 2) {
      return {
        metric,
        status: "insufficient-measurements",
        fromValue: measuredRows[0]?.value ?? null,
        toValue: null,
        fromAvg: measuredRows[0]?.metrics[metric] ?? null,
        toAvg: null,
        delta: null,
        pctDelta: null,
      };
    }
    const first = measuredRows[0];
    const last = measuredRows[measuredRows.length - 1];
    const fromAvg = first.metrics[metric];
    const toAvg = last.metrics[metric];
    return {
      metric,
      status: "measured",
      fromValue: first.value,
      toValue: last.value,
      fromAvg,
      toAvg,
      delta: fixed(toAvg - fromAvg),
      pctDelta: fromAvg === 0 ? null : fixed(((toAvg - fromAvg) / Math.abs(fromAvg)) * 100),
    };
  });
}

function influenceCaveats(group, rows, profileDiagnostics, metricEffects) {
  const caveats = [];
  if (profileDiagnostics === undefined) caveats.push("missing-profile-diagnostics");
  if (rows.some((row) => row.observationCount === 0)) caveats.push("missing-configured-value-observations");
  if (metricEffects.every((entry) => entry.status !== "measured" || entry.delta === 0)) caveats.push("no-measured-metric-movement");
  if (profileDiagnostics?.inferenceQuality === "insufficient-variation") caveats.push("insufficient-profile-variation");
  caveats.push("observational-summary; repeat across seeds before scoring policy changes");
  return caveats;
}

function writeCalibrationEntry({ id, description, mode, botCatalog, generatedDir, groupId, targetProfileId, knob, value }) {
  const botCatalogPath = join(generatedDir, `${id}.bot-catalog.json`);
  const modePath = join(generatedDir, `${id}.mode.json`);
  const modeOverlay = {
    ...mode,
    modeId: `${mode.modeId}-actor-${id}`,
    scenarioId: `${mode.scenarioId}-actor-${id}`,
    venueSessionId: `${mode.venueSessionId}-actor-${id}`,
    catalogPath: botCatalogPath,
  };
  writeJson(botCatalogPath, botCatalog);
  writeJson(modePath, modeOverlay);
  return {
    id,
    description,
    groupId,
    targetProfileId,
    knob,
    value,
    modePath,
    botCatalogPath,
  };
}

function selectedGroups(groupIds) {
  if (groupIds === undefined || groupIds.length === 0) return DEFAULT_GROUPS;
  const byId = new Map(DEFAULT_GROUPS.map((group) => [group.id, group]));
  return groupIds.map((id) => {
    const group = byId.get(id);
    if (group === undefined) {
      throw new Error(`unknown actor calibration group ${id}; known groups: ${Array.from(byId.keys()).join(", ")}`);
    }
    return group;
  });
}

function selectedEnvironment(environmentId = "default") {
  const environment = CALIBRATION_ENVIRONMENTS[environmentId];
  if (environment === undefined) {
    throw new Error(`unknown actor calibration environment ${environmentId}; known environments: ${Object.keys(CALIBRATION_ENVIRONMENTS).join(", ")}`);
  }
  return environment;
}

function actorCalibrationCatalogId(catalogId) {
  const base = catalogId ?? "bot-catalog";
  return base.endsWith("-actor-calibration") ? base : `${base}-actor-calibration`;
}

function environmentDescriptor(environment) {
  return {
    id: environment.id,
    description: environment.description,
    modePatch: environment.modePatch ?? {},
    actorProfileParamOverrides: environment.actorProfileParamOverrides ?? {},
  };
}

function runArenaEntry(entry, reportPath, options) {
  const args = buildArenaRunArgs(entry, reportPath, options);
  const spawned = spawnSync(options.runtime ?? "bun", args, {
    cwd: options.cwd ?? repoRoot(),
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (spawned.status !== 0) {
    return {
      status: "failed",
      exitCode: spawned.status ?? 1,
      error: [spawned.stdout, spawned.stderr].filter(Boolean).join("\n").trim(),
      report: null,
    };
  }
  const report = readJson(reportPath);
  return {
    status: report.status ?? "completed",
    exitCode: 0,
    error: "",
    report,
  };
}

function reportSummary(report) {
  return {
    runId: report.runId ?? "",
    status: report.status ?? "",
    modeId: report.mode?.modeId ?? "",
    policyEnvelopeHash: report.policyEnvelopeHash ?? "",
    profileCount: report.runPlan?.actorProfiles?.profiles?.length ?? 0,
    npcDifficultyBuckets: report.scoringCalibration?.difficultyContext?.npcDifficultyBuckets ?? [],
    difficultyMultiplier: report.scoringCalibration?.difficultyContext?.difficultyMultiplier ?? null,
    totalFills: report.executionSummary?.totalFills ?? null,
    flags: report.scoringCalibration?.dataQuality?.flags ?? [],
  };
}

function isCompletedRunStatus(status) {
  return typeof status === "string" && status.startsWith("completed");
}

function profileIdForBot(bot, mode) {
  return bot.actorProfileRef ?? mode.actorProfileDefaults?.[bot.role] ?? "";
}

function valueToken(value) {
  return String(value).toLowerCase().replace(/\./g, "p").replace(/[^a-z0-9_-]/g, "-");
}

function repoRoot() {
  return resolve(dirname(fileURLToPath(import.meta.url)), "../..");
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function deepMerge(base, overlay) {
  const merged = { ...base };
  for (const [key, value] of Object.entries(overlay ?? {})) {
    if (isPlainObject(value) && isPlainObject(base?.[key])) {
      merged[key] = deepMerge(base[key], value);
    } else if (Array.isArray(value)) {
      merged[key] = [...value];
    } else if (isPlainObject(value)) {
      merged[key] = deepMerge({}, value);
    } else {
      merged[key] = value;
    }
  }
  return merged;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function writeJson(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function positiveNumber(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

function fixed(value) {
  return Number(value.toFixed(6));
}
