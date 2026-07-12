import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [basePath, candidatePath] = process.argv.slice(2);
  if (!basePath || !candidatePath) {
    console.error("usage: bun scripts/dev/compare-arena-scoring-calibration.mjs BASE_REPORT.json CANDIDATE_REPORT.json");
    process.exit(1);
  }
  const base = JSON.parse(readFileSync(basePath, "utf8"));
  const candidate = JSON.parse(readFileSync(candidatePath, "utf8"));
  console.log(JSON.stringify(compareArenaScoringCalibration(base, candidate), null, 2));
}

export function compareArenaScoringCalibration(baseReport, candidateReport) {
  const base = calibrationFromReport(baseReport);
  const candidate = calibrationFromReport(candidateReport);
  const componentDeltas = {};
  for (const component of ["equity", "risk", "conduct", "marketInteraction", "difficulty"]) {
    componentDeltas[component] = statDelta(base.scoreDistribution?.components?.[component], candidate.scoreDistribution?.components?.[component]);
  }
  const diagnosticDeltas = {};
  for (const diagnostic of ["fillRatio", "completionRate", "pnlPerExecutedNotionalBps", "inventoryExposureRatio", "inventoryConcentration"]) {
    diagnosticDeltas[diagnostic] = statDelta(base.scoreDistribution?.diagnostics?.[diagnostic], candidate.scoreDistribution?.diagnostics?.[diagnostic]);
  }
  const topComponentMove = Object.entries(componentDeltas)
    .sort(([, left], [, right]) => Math.abs(right.avgDelta ?? 0) - Math.abs(left.avgDelta ?? 0))[0] ?? null;
  return {
    schemaVersion: "reef.arena.scoringCalibrationComparison.v1",
    base: reportIdentity(baseReport, base),
    candidate: reportIdentity(candidateReport, candidate),
    formulaVersionChanged: base.formulaVersion !== candidate.formulaVersion,
    scoringPolicyVersionChanged: base.scoringPolicyVersion !== candidate.scoringPolicyVersion,
    eligibilityDelta: {
      totalBots: numberValue(candidate.eligibility?.totalBots) - numberValue(base.eligibility?.totalBots),
      eligibleCompetitors: numberValue(candidate.eligibility?.eligibleCompetitors) - numberValue(base.eligibility?.eligibleCompetitors),
      nonScoringActors: numberValue(candidate.eligibility?.nonScoringActors) - numberValue(base.eligibility?.nonScoringActors),
    },
    difficultyMultiplierDelta: nullableDelta(base.difficultyContext?.difficultyMultiplier, candidate.difficultyContext?.difficultyMultiplier),
    scoreDeltas: {
      publicScore: statDelta(base.scoreDistribution?.publicScore, candidate.scoreDistribution?.publicScore),
      shadowScore: statDelta(base.scoreDistribution?.shadowScore, candidate.scoreDistribution?.shadowScore),
      components: componentDeltas,
      diagnostics: diagnosticDeltas,
    },
    topComponentMove: topComponentMove === null ? null : {
      component: topComponentMove[0],
      avgDelta: topComponentMove[1].avgDelta,
    },
    dataQuality: {
      baseFlags: base.dataQuality?.flags ?? [],
      candidateFlags: candidate.dataQuality?.flags ?? [],
      addedFlags: difference(candidate.dataQuality?.flags, base.dataQuality?.flags),
      removedFlags: difference(base.dataQuality?.flags, candidate.dataQuality?.flags),
      publicScoreStillUnchanged: base.dataQuality?.publicScoreUnchanged === true && candidate.dataQuality?.publicScoreUnchanged === true,
    },
  };
}

function calibrationFromReport(report) {
  return report?.scoringCalibration ?? {};
}

function reportIdentity(report, calibration) {
  return {
    runId: report?.runId ?? "",
    modeId: report?.mode?.modeId ?? report?.policyEnvelope?.modeId ?? "",
    policyEnvelopeHash: report?.policyEnvelopeHash ?? "",
    formulaVersion: calibration.formulaVersion ?? "",
    scoringPolicyVersion: calibration.scoringPolicyVersion ?? "",
    npcDifficultyBuckets: calibration.difficultyContext?.npcDifficultyBuckets ?? report?.mode?.npcDifficultyBuckets ?? [],
  };
}

function statDelta(base, candidate) {
  return {
    countDelta: numberValue(candidate?.count) - numberValue(base?.count),
    minDelta: nullableDelta(base?.min, candidate?.min),
    maxDelta: nullableDelta(base?.max, candidate?.max),
    avgDelta: nullableDelta(base?.avg, candidate?.avg),
  };
}

function nullableDelta(base, candidate) {
  const left = nullableNumber(base);
  const right = nullableNumber(candidate);
  return left === null || right === null ? null : Number((right - left).toFixed(6));
}

function difference(left = [], right = []) {
  const rightSet = new Set(Array.isArray(right) ? right : []);
  return (Array.isArray(left) ? left : []).filter((value) => !rightSet.has(value)).sort();
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
