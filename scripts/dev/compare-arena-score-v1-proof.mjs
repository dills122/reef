import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const DEFAULT_HEALTH_EMPTY_DELTA = 5;
const DEFAULT_HEALTH_PCT_DELTA = 1;

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { basePath, candidatePath, options } = parseArgs(process.argv.slice(2));
  if (!basePath || !candidatePath) {
    console.error("usage: node scripts/dev/compare-arena-score-v1-proof.mjs BASE_REPORT.json CANDIDATE_REPORT.json [--max-health-empty-delta=5] [--max-health-pct-delta=1]");
    process.exit(1);
  }
  const base = JSON.parse(readFileSync(basePath, "utf8"));
  const candidate = JSON.parse(readFileSync(candidatePath, "utf8"));
  const comparison = compareArenaScoreV1Proof(base, candidate, options);
  console.log(JSON.stringify(comparison, null, 2));
  if (comparison.status !== "pass") {
    process.exit(1);
  }
}

export function compareArenaScoreV1Proof(baseReport, candidateReport, options = {}) {
  const maxHealthEmptyDelta = numberOption(options.maxHealthEmptyDelta, DEFAULT_HEALTH_EMPTY_DELTA);
  const maxHealthPctDelta = numberOption(options.maxHealthPctDelta, DEFAULT_HEALTH_PCT_DELTA);
  const baseProof = deterministicProofFields(baseReport);
  const candidateProof = deterministicProofFields(candidateReport);
  const exactMatches = {};
  const failures = [];

  for (const field of Object.keys(baseProof)) {
    const matched = stableString(baseProof[field]) === stableString(candidateProof[field]);
    exactMatches[field] = matched;
    if (!matched) failures.push(`${field} differs`);
  }

  const health = compareHealth(baseReport, candidateReport, {
    maxEmptyDelta: maxHealthEmptyDelta,
    maxPctDelta: maxHealthPctDelta,
  });
  failures.push(...health.failures.map((failure) => `health: ${failure}`));

  return {
    schemaVersion: "reef.arena.scoreV1ProofComparison.v1",
    status: failures.length === 0 ? "pass" : "fail",
    failures,
    exactMatches,
    deterministicHash: {
      base: hashJson(baseProof),
      candidate: hashJson(candidateProof),
      matched: stableString(baseProof) === stableString(candidateProof),
    },
    health,
    base: reportIdentity(baseReport),
    candidate: reportIdentity(candidateReport),
  };
}

function deterministicProofFields(report) {
  return {
    status: report?.status ?? "",
    totals: report?.totals ?? {},
    commandAccounting: report?.commandAccounting ?? {},
    commandRoutes: report?.commandStatusSummary?.byRoute ?? {},
    commandFinal: report?.commandStatusSummary?.byFinalStatus ?? {},
    execution: {
      fillCount: report?.executionSummary?.fillCount ?? 0,
      byInstrument: report?.executionSummary?.byInstrument ?? {},
      byRole: report?.executionSummary?.byRole ?? {},
    },
    scoring: {
      mode: report?.scoringCalibration?.mode ?? "",
      formulaVersion: report?.scoringCalibration?.formulaVersion ?? "",
      scoringPolicyVersion: report?.scoringCalibration?.scoringPolicyVersion ?? "",
      flags: report?.scoringCalibration?.dataQuality?.flags ?? [],
      publicScoreMismatchCount: report?.scoringCalibration?.dataQuality?.publicScoreMismatchCount ?? 0,
      pnlAvailableCount: report?.scoringCalibration?.dataQuality?.pnlAvailableCount ?? 0,
      fillCount: report?.scoringCalibration?.dataQuality?.fillCount ?? 0,
      publicScore: report?.scoringCalibration?.scoreDistribution?.publicScore ?? {},
    },
    leaderboard: (report?.leaderboard ?? []).map((entry) => ({
      rank: entry.rank,
      botId: entry.botId,
      score: entry.score,
      disqualified: entry.disqualified === true,
    })),
    botActivity: Object.fromEntries(
      (report?.botResults ?? [])
        .map((result) => [
          result.botId,
          {
            score: result.score,
            disqualified: result.disqualified === true,
            venueCommands: result.venueCommands ?? 0,
            submitted: result.tradingMetrics?.commands?.submitted ?? 0,
            byRoute: result.tradingMetrics?.commands?.byRoute ?? result.tradingMetrics?.commands?.submittedByRoute ?? {},
            submit: result.conductMetrics?.submitCommands ?? 0,
            cancel: result.conductMetrics?.cancelCommands ?? 0,
          },
        ])
        .sort(([left], [right]) => left.localeCompare(right)),
    ),
  };
}

function compareHealth(baseReport, candidateReport, options) {
  const failures = [];
  const base = healthFields(baseReport);
  const candidate = healthFields(candidateReport);
  if (base.status !== candidate.status) failures.push(`status differs (${base.status} vs ${candidate.status})`);
  if (base.status !== "pass") failures.push(`base status is ${base.status}`);
  if (candidate.status !== "pass") failures.push(`candidate status is ${candidate.status}`);
  if (base.marketQualityStatus !== candidate.marketQualityStatus) failures.push(`marketQualityStatus differs (${base.marketQualityStatus} vs ${candidate.marketQualityStatus})`);
  if (base.marketQualityStatus !== "pass") failures.push(`base marketQualityStatus is ${base.marketQualityStatus}`);
  if (candidate.marketQualityStatus !== "pass") failures.push(`candidate marketQualityStatus is ${candidate.marketQualityStatus}`);
  if (base.sampleCount !== candidate.sampleCount) failures.push(`sampleCount differs (${base.sampleCount} vs ${candidate.sampleCount})`);
  if (base.postWarmupSampleCount !== candidate.postWarmupSampleCount) {
    failures.push(`postWarmupSampleCount differs (${base.postWarmupSampleCount} vs ${candidate.postWarmupSampleCount})`);
  }
  if (base.crossedBookCount !== candidate.crossedBookCount) failures.push(`crossedBookCount differs (${base.crossedBookCount} vs ${candidate.crossedBookCount})`);
  if (base.lockedBookCount !== candidate.lockedBookCount) failures.push(`lockedBookCount differs (${base.lockedBookCount} vs ${candidate.lockedBookCount})`);
  if (Math.abs(base.emptyBookCount - candidate.emptyBookCount) > options.maxEmptyDelta) {
    failures.push(`emptyBookCount delta ${Math.abs(base.emptyBookCount - candidate.emptyBookCount)} > ${options.maxEmptyDelta}`);
  }
  if (Math.abs(base.topOfBookPct - candidate.topOfBookPct) > options.maxPctDelta) {
    failures.push(`topOfBookPct delta ${round6(Math.abs(base.topOfBookPct - candidate.topOfBookPct))} > ${options.maxPctDelta}`);
  }
  if (Math.abs(base.depthPct - candidate.depthPct) > options.maxPctDelta) {
    failures.push(`depthPct delta ${round6(Math.abs(base.depthPct - candidate.depthPct))} > ${options.maxPctDelta}`);
  }
  return {
    status: failures.length === 0 ? "pass" : "fail",
    failures,
    tolerance: {
      maxEmptyDelta: options.maxEmptyDelta,
      maxPctDelta: options.maxPctDelta,
    },
    base,
    candidate,
    delta: {
      emptyBookCount: candidate.emptyBookCount - base.emptyBookCount,
      topOfBookPct: round6(candidate.topOfBookPct - base.topOfBookPct),
      depthPct: round6(candidate.depthPct - base.depthPct),
    },
  };
}

function healthFields(report) {
  const health = report?.healthSummary ?? {};
  return {
    status: health.status ?? "unknown",
    marketQualityStatus: report?.marketQualitySummary?.status ?? "unknown",
    sampleCount: numberValue(health.sampleCount),
    postWarmupSampleCount: numberValue(health.postWarmupSampleCount),
    topOfBookPct: numberValue(health.topOfBookPct),
    depthPct: numberValue(health.depthPct),
    crossedBookCount: numberValue(health.crossedBookCount),
    lockedBookCount: numberValue(health.lockedBookCount),
    emptyBookCount: numberValue(health.emptyBookCount),
  };
}

function reportIdentity(report) {
  return {
    runId: report?.runId ?? "",
    modeId: report?.mode?.modeId ?? "",
    scoringPolicyVersion: report?.mode?.scoringPolicyVersion ?? report?.scoringCalibration?.scoringPolicyVersion ?? "",
    durationSeconds: report?.runPlan?.durationSeconds ?? null,
    reportShape: report?.reportShape ?? "full",
  };
}

function parseArgs(args) {
  const paths = [];
  const options = {};
  for (const arg of args) {
    if (arg.startsWith("--max-health-empty-delta=")) {
      options.maxHealthEmptyDelta = Number(arg.slice("--max-health-empty-delta=".length));
    } else if (arg.startsWith("--max-health-pct-delta=")) {
      options.maxHealthPctDelta = Number(arg.slice("--max-health-pct-delta=".length));
    } else {
      paths.push(arg);
    }
  }
  return {
    basePath: paths[0],
    candidatePath: paths[1],
    options,
  };
}

function hashJson(value) {
  return createHash("sha256").update(stableString(value)).digest("hex");
}

function stableString(value) {
  return JSON.stringify(sortValue(value));
}

function sortValue(value) {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value === null || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, child]) => [key, sortValue(child)]),
  );
}

function numberOption(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function numberValue(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function round6(value) {
  return Number(value.toFixed(6));
}
