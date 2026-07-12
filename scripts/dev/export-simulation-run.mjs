import { createHash } from "node:crypto";
import { readdir, readFile, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { env, loadDotEnv, deriveDevUrls } from "./lib/dev-utils.mjs";

export async function buildSimulationRunExport(options) {
  const reportPath = path.resolve(options.reportPath);
  const report = JSON.parse(await readFile(reportPath, "utf8"));
  const artifacts = options.artifactRoot
    ? await artifactManifest(path.resolve(options.artifactRoot))
    : [await fileArtifact(reportPath, path.dirname(reportPath))];
  const counts = exportCounts(report);
  const latencyMs = exportLatency(report);
  const runId = firstNonBlank(
    options.runId,
    report.runId,
    report.sessionId,
    report.config?.RunID,
    report.config?.ScenarioRunID,
    inferredRunId(reportPath),
  );
  const scenarioId = firstNonBlank(
    options.scenarioId,
    report.scenarioId,
    report.config?.ScenarioID,
    report.config?.scenarioId,
    report.bestByThroughput?.scenarioId,
    runId,
  );

  return {
    runId,
    scenarioId,
    runKind: firstNonBlank(options.runKind, report.runKind, report.config?.RunKind, inferredRunKind(reportPath)),
    source: firstNonBlank(options.source, env("REEF_EXPORT_SOURCE", "local")),
    gitSha: firstNonBlank(options.gitSha, env("REEF_EXPORT_GIT_SHA", "")),
    profile: firstNonBlank(options.profile, report.profile, report.config?.Profile, inferredProfile(report)),
    startedAt: firstNonBlank(options.startedAt, report.startedAt, report.startTime, ""),
    completedAt: firstNonBlank(options.completedAt, report.finishedAt, report.completedAt, report.generatedAt, ""),
    status: firstNonBlank(options.status, inferredStatus(report)),
    counts,
    latencyMs,
    artifacts,
    summary: compactSummary(report, reportPath),
  };
}

export function exportCounts(report) {
  if (isArenaLocalTickReport(report)) {
    const totals = report.totals ?? {};
    const projections = report.venueReadback?.availability?.body?.projections;
    const projected = Array.isArray(projections)
      ? projections.reduce((sum, projection) => sum + numberOrZero(projection.projectedCount), 0)
      : 0;
    return {
      attempted: numberOrZero(totals.venueCommands),
      accepted: numberOrZero(totals.submittedCommands),
      completed: numberOrZero(totals.completedCommands),
      materialized: projected,
      projected,
      failed: numberOrZero(totals.failedCommands) + numberOrZero(totals.rejectedCommands) + numberOrZero(totals.timedOutCommands),
    };
  }
  const statusCodes = report.statusCodes ?? {};
  return {
    attempted: numberOrZero(report.totalRequests ?? report.quality?.totalRequests),
    accepted: numberOrZero(statusCodes["202"] ?? statusCodes["200"] ?? report.totalSuccess ?? report.quality?.totalSuccess),
    completed: numberOrZero(report.totalSuccess ?? report.quality?.totalSuccess),
    materialized: numberOrZero(report.materializedCount ?? report.materialized ?? report.venueEventMaterializer?.materialized),
    projected: numberOrZero(report.projectedCount ?? report.projected ?? report.projector?.projected),
    failed: numberOrZero(report.totalFailures ?? report.quality?.totalFailures),
  };
}

export function exportLatency(report) {
  if (isArenaLocalTickReport(report)) {
    if (report.latencySummary?.tickElapsedMs !== undefined) {
      return {
        p50: nullableNumber(report.latencySummary.tickElapsedMs.p50),
        p95: nullableNumber(report.latencySummary.tickElapsedMs.p95),
        p99: nullableNumber(report.latencySummary.tickElapsedMs.p99),
      };
    }
    const latencies = report.sessionReports
      ?.flatMap((session) => session.ticks ?? [])
      ?.map((tick) => Number(tick.elapsedMs ?? 0))
      ?.filter((value) => Number.isFinite(value))
      ?.sort((left, right) => left - right) ?? [];
    return {
      p50: percentile(latencies, 0.5),
      p95: percentile(latencies, 0.95),
      p99: percentile(latencies, 0.99),
    };
  }
  const latency = report.latencyMs ?? report.bestByThroughput ?? report.averages ?? {};
  return {
    p50: nullableNumber(latency.p50 ?? latency.p50LatencyMs),
    p95: nullableNumber(latency.p95 ?? latency.p95LatencyMs ?? latency.p95Ms),
    p99: nullableNumber(latency.p99 ?? latency.p99LatencyMs ?? latency.p99Ms),
  };
}

export async function artifactManifest(rootPath) {
  const rootStat = await stat(rootPath);
  if (rootStat.isFile()) {
    return [await fileArtifact(rootPath, path.dirname(rootPath))];
  }
  const files = await listFiles(rootPath);
  const artifacts = [];
  for (const file of files) {
    artifacts.push(await fileArtifact(file, rootPath));
  }
  return artifacts;
}

async function main() {
  loadDotEnv();
  const options = parseArgs(process.argv.slice(2));
  if (!options.reportPath) {
    usage(1);
  }
  const payload = await buildSimulationRunExport(options);
  if (options.outPath) {
    await writeFile(path.resolve(options.outPath), `${JSON.stringify(payload, null, 2)}\n`);
  }
  if (!options.post) {
    if (options.outPath) {
      console.log(`wrote ${path.resolve(options.outPath)}`);
    } else {
      console.log(JSON.stringify(payload, null, 2));
    }
    return;
  }
  const apiUrl = firstNonBlank(options.apiUrl, env("REEF_EXPORT_API_URL", ""), env("REEF_BACKBONE_ADMIN_API_URL", ""), deriveDevUrls().runtimeUrl);
  const response = await postExport(apiUrl, payload, options.token ?? env("REEF_EXPORT_API_TOKEN", ""));
  console.log(JSON.stringify(response, null, 2));
}

export function parseArgs(args) {
  const options = { post: false };
  for (let i = 0; i < args.length; i += 1) {
    const arg = args[i];
    if (arg === "--post") options.post = true;
    else if (arg === "--dry-run") options.post = false;
    else if (arg === "--report") options.reportPath = args[++i];
    else if (arg === "--artifact-root") options.artifactRoot = args[++i];
    else if (arg === "--api-url") options.apiUrl = args[++i];
    else if (arg === "--token") options.token = args[++i];
    else if (arg === "--out") options.outPath = args[++i];
    else if (arg === "--run-id") options.runId = args[++i];
    else if (arg === "--scenario-id") options.scenarioId = args[++i];
    else if (arg === "--run-kind") options.runKind = args[++i];
    else if (arg === "--source") options.source = args[++i];
    else if (arg === "--git-sha") options.gitSha = args[++i];
    else if (arg === "--profile") options.profile = args[++i];
    else if (arg === "--started-at") options.startedAt = args[++i];
    else if (arg === "--completed-at") options.completedAt = args[++i];
    else if (arg === "--status") options.status = args[++i];
    else if (arg === "--help" || arg === "-h") usage(0);
    else if (!options.reportPath) options.reportPath = arg;
    else throw new Error(`unknown argument: ${arg}`);
  }
  return options;
}

async function postExport(apiUrl, payload, token) {
  const headers = {
    "content-type": "application/json",
    "X-Reef-Actor-Id": "simulation-run-exporter",
  };
  if (token) headers.authorization = `Bearer ${token}`;
  const response = await fetch(`${apiUrl.replace(/\/$/, "")}/admin/v1/analytics/run-exports`, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`run export post failed (${response.status}): ${text}`);
  }
  return text ? JSON.parse(text) : {};
}

async function listFiles(rootPath) {
  const entries = await readdir(rootPath, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const fullPath = path.join(rootPath, entry.name);
    if (entry.isDirectory()) {
      files.push(...await listFiles(fullPath));
    } else if (entry.isFile()) {
      files.push(fullPath);
    }
  }
  return files.sort();
}

async function fileArtifact(filePath, rootPath) {
  const raw = await readFile(filePath);
  const fileStat = await stat(filePath);
  return {
    type: artifactType(filePath),
    path: path.relative(rootPath, filePath) || path.basename(filePath),
    sizeBytes: fileStat.size,
    sha256: createHash("sha256").update(raw).digest("hex"),
  };
}

function compactSummary(report, reportPath) {
  if (isArenaLocalTickReport(report)) {
    return {
      reportPath,
      generatedAt: report.generatedAt ?? "",
      runId: report.runId ?? "",
      mode: report.mode ?? {},
      policyEnvelopeHash: report.policyEnvelopeHash ?? "",
      policyEnvelope: compactPolicyEnvelope(report.policyEnvelope),
      actorProfiles: report.runPlan?.actorProfiles ?? {},
      runnerProfile: report.runnerProfile ?? {},
      status: report.status ?? "",
      totals: report.totals ?? {},
      commandAccounting: report.commandAccounting ?? {},
      commandStatusSummary: report.commandStatusSummary ?? {},
      healthSummary: report.healthSummary ?? {},
      venueReadback: {
        mode: report.venueReadback?.mode ?? "",
        skipped: Boolean(report.venueReadback?.skipped),
        projectionDrained: Boolean(report.venueReadback?.projectionDrained),
      },
      botResults: Array.isArray(report.botResults)
        ? report.botResults.map((bot) => ({
          botId: bot.botId,
          role: bot.role,
          finalEquity: nullableNumber(bot.finalEquity ?? bot.score),
          realizedPnl: nullableNumber(bot.realizedPnl),
          maxDrawdown: nullableNumber(bot.maxDrawdown),
          ticksRun: numberOrZero(bot.ticksRun),
          venueCommands: numberOrZero(bot.venueCommands),
          failedTicks: numberOrZero(bot.failedTicks),
          freezeCount: numberOrZero(bot.freezeCount),
          disqualified: Boolean(bot.disqualified),
          tradingMetrics: compactTradingMetrics(bot.tradingMetrics),
          conductMetrics: compactConductMetrics(bot.conductMetrics),
          actorClass: bot.actorClass ?? "",
          actorProfile: compactActorProfile(bot.actorProfile),
        }))
        : [],
      settlementScore: report.settlementScore ?? report.settlementScoreSummary ?? null,
      enforcementEvents: Array.isArray(report.enforcementEvents) ? report.enforcementEvents.length : 0,
    };
  }
  return {
    reportPath,
    generatedAt: report.generatedAt ?? "",
    sessionId: report.sessionId ?? "",
    durationSeconds: nullableNumber(report.durationSeconds),
    throughputRps: nullableNumber(report.throughputRps ?? report.averages?.throughputRps ?? report.bestByThroughput?.throughputRps),
    acceptedBusinessOpsRps: nullableNumber(
      report.acceptedBusinessOpsRps ?? report.averages?.acceptedBusinessOpsRps ?? report.bestByThroughput?.acceptedBusinessOpsRps,
    ),
    statusCodes: report.statusCodes ?? {},
    quality: report.quality ?? qualityFromKpi(report),
    traceChecks: compactTraceChecks(report.traceChecks),
    rejectTaxonomy: report.rejectTaxonomy ?? report.bestByThroughput?.rejectTaxonomy ?? [],
    bestByThroughput: report.bestByThroughput ?? null,
    bestByAccepted: report.bestByAccepted ?? null,
  };
}

function isArenaLocalTickReport(report) {
  return report?.schemaVersion === "reef.arena.localTickRun.v0";
}

function compactTradingMetrics(metrics) {
  if (!metrics || typeof metrics !== "object") return undefined;
  return {
    schemaVersion: metrics.schemaVersion ?? "",
    commands: metrics.commands ?? {},
    pnl: metrics.pnl ?? {},
  };
}

function compactPolicyEnvelope(envelope) {
  if (!envelope || typeof envelope !== "object") return {};
  return {
    schemaVersion: envelope.schemaVersion ?? "",
    modeId: envelope.modeId ?? "",
    modeVersion: envelope.modeVersion ?? "",
    scenarioId: envelope.scenarioId ?? "",
    seed: nullableNumber(envelope.seed),
    scoringPolicyVersion: envelope.scoringPolicyVersion ?? "",
    economicPolicyVersion: envelope.economicPolicyVersion ?? "",
    liquidityPolicyVersion: envelope.liquidityPolicyVersion ?? "",
    backgroundFlowPolicyVersion: envelope.backgroundFlowPolicyVersion ?? "",
    creditPolicyVersion: envelope.creditPolicyVersion ?? "",
    interventionPolicyVersion: envelope.interventionPolicyVersion ?? "",
    npcDifficultyBuckets: Array.isArray(envelope.npcDifficultyBuckets) ? envelope.npcDifficultyBuckets : [],
  };
}

function compactActorProfile(profile) {
  if (!profile || typeof profile !== "object") return undefined;
  return {
    profileId: profile.profileId ?? "",
    profileVersion: profile.profileVersion ?? "",
    actorClass: profile.actorClass ?? "",
    difficultyBucket: profile.difficultyBucket ?? "",
    scoreEffect: profile.scoreEffect ?? "",
    profileHash: profile.profileHash ?? "",
  };
}

function compactConductMetrics(metrics) {
  if (!metrics || typeof metrics !== "object") return undefined;
  return {
    schemaVersion: metrics.schemaVersion ?? "",
    status: metrics.status ?? "",
    orderCommands: numberOrZero(metrics.orderCommands),
    cancelReplaceRatio: nullableNumber(metrics.cancelReplaceRatio),
    invalidIntentRate: nullableNumber(metrics.invalidIntentRate),
    timeoutRate: nullableNumber(metrics.timeoutRate),
    maxActionsPerTick: numberOrZero(metrics.maxActionsPerTick),
    maxVenueCommandsPerTick: numberOrZero(metrics.maxVenueCommandsPerTick),
    freezeCount: numberOrZero(metrics.freezeCount),
  };
}

function compactTraceChecks(traceChecks) {
  if (!traceChecks) return {};
  return {
    checked: numberOrZero(traceChecks.checked),
    pass: numberOrZero(traceChecks.pass),
    fail: numberOrZero(traceChecks.fail),
    failedTraceIds: Array.isArray(traceChecks.failedTraceIds) ? traceChecks.failedTraceIds.slice(0, 20) : [],
  };
}

function qualityFromKpi(report) {
  const averages = report.averages ?? {};
  return {
    endToEndSuccessRatePct: nullableNumber(averages.endToEndSuccessRatePct),
    validIntentSuccessRatePct: nullableNumber(averages.validIntentSuccessRatePct),
    systemFailureRatePct: nullableNumber(averages.systemFailureRateProxyPct),
    tracePassRatePct: nullableNumber(averages.tracePassRatePct),
  };
}

function inferredRunId(reportPath) {
  const base = path.basename(reportPath, ".json");
  if (base.includes("kpi") || base.includes("recommendation")) {
    return path.basename(path.dirname(reportPath));
  }
  return base;
}

function inferredRunKind(reportPath) {
  const base = path.basename(reportPath).toLowerCase();
  if (base.includes("stress")) return "stress";
  if (base.includes("throughput")) return "throughput-campaign";
  return "simulation";
}

function inferredProfile(report) {
  const rate = report.rate ?? report.config?.RatePerSecond ?? report.bestByThroughput?.rate ?? report.bestByAccepted?.rate;
  const workers = report.workers ?? report.config?.Workers ?? report.bestByThroughput?.workers ?? report.bestByAccepted?.workers;
  if (rate && workers) return `${rate}/s-workers-${workers}`;
  return firstNonBlank(report.config?.SessionName, report.config?.Mode, "");
}

function inferredStatus(report) {
  if (report.status) return report.status;
  if (numberOrZero(report.totalRequests) > 0 || numberOrZero(report.sampleCount) > 0) return "completed";
  return "unknown";
}

function artifactType(filePath) {
  const name = path.basename(filePath).toLowerCase();
  if (name.endsWith(".md")) return "markdown";
  if (name.endsWith(".ndjson")) return "telemetry";
  if (name.endsWith(".yaml") || name.endsWith(".yml")) return "config";
  if (name.endsWith(".json")) return "json";
  return "artifact";
}

function firstNonBlank(...values) {
  for (const value of values) {
    if (value !== undefined && value !== null && String(value).trim() !== "") return String(value);
  }
  return "";
}

function nullableNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function percentile(sortedValues, pct) {
  if (!Array.isArray(sortedValues) || sortedValues.length === 0) return null;
  const index = Math.min(sortedValues.length - 1, Math.ceil(sortedValues.length * pct) - 1);
  return sortedValues[index];
}

function numberOrZero(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
}

function usage(code) {
  console.log(`usage: scripts/dev/export-simulation-run.mjs --report <path> [--artifact-root <dir>] [--post] [--api-url <url>]

Defaults to dry-run JSON output. Use --post to send to /admin/v1/analytics/run-exports.`);
  process.exit(code);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
