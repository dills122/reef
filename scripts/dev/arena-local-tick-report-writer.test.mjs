import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-local-tick-report-writer-"));
const reportPath = join(dir, "arena-local-tick-run.json");
const compactReportPath = join(dir, "arena-local-tick-run-compact.json");
const pacedReportPath = join(dir, "arena-local-tick-run-paced.json");
const scoreV1ReportPath = join(dir, "arena-local-tick-run-score-v1.json");

const result = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--duration-seconds=1",
    "--tick-interval-ms=500",
    `--out=${reportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
const report = JSON.parse(readFileSync(reportPath, "utf8"));
assert.equal(report.schemaVersion, "reef.arena.localTickRun.v0");
assert.equal(report.status, "completed");
assert.equal(report.runPlan.durationSeconds, 1);
assert.equal(report.runPlan.schedulingMode, "shared-arena-time");
assert.equal(report.totals.ticks, 16);
assert.equal(report.pacingSummary.schemaVersion, "reef.arena.pacingSummary.v0");
assert.equal(report.pacingSummary.enabled, false);
assert.equal(report.pacingSummary.scheduler, "unpaced");
assert.equal(report.pacingSummary.scheduledDurationMs, 1000);
assert.equal(report.pacingSummary.scheduledEventCount, 4);
assert.equal(report.projectionDrainCadence, "per-submission");
assert.equal(report.sessionReports.length, 5);
assert.equal(report.sessionReports.flatMap((session) => session.ticks).length, report.totals.ticks);
assert.equal(report.healthSamples.length, 2);
assert.equal(report.mode.economicPolicyVersion, "economic-v0");
assert.equal(report.mode.actorProfileCatalogVersion, "2026-07-12");
assert.deepEqual(report.mode.npcDifficultyBuckets, ["benign-noise"]);
assert.equal(report.policyEnvelope.schemaVersion, "reef.arena.policyEnvelope.v1");
assert.equal(report.policyEnvelope.economicPolicyVersion, "economic-v0");
assert.equal(report.policyEnvelope.actorProfiles.length, 5);
assert.ok(/^sha256:[a-f0-9]{64}$/.test(report.policyEnvelopeHash));
assert.equal(report.runPlan.actorProfiles.schemaVersion, "reef.arena.actorProfileSummary.v1");
assert.deepEqual(report.runPlan.actorProfiles.byActorClass, {
  competitor: 1,
  house_market_maker: 3,
  npc_flow: 1,
});
assert.equal(report.runPlan.actorProfiles.byDifficultyBucket["neutral-liquidity"], 3);
assert.ok(report.runPlan.actorProfiles.profiles.every((entry) => /^sha256:[a-f0-9]{64}$/.test(entry.profileHash)));
assert.equal(report.botResults.find((entry) => entry.botId === "builtin-npc-momentum").actorProfile.profileId, "npc-noise-small-random");
assert.equal(report.botResults[0].conductMetrics.schemaVersion, "reef.arena.conductMetrics.v0");
assert.equal(report.botResults[0].conductMetrics.status, "reported");
assert.equal(report.scoringAssumptions.npcDifficultyMode, "leaderboard-partition-plus-shadow-multiplier");
assert.equal(report.scoringCalibration.schemaVersion, "reef.arena.scoringCalibration.v1");
assert.equal(report.scoringCalibration.formulaVersion, "shadow-score-v1");
assert.equal(report.scoringCalibration.eligibility.eligibleCompetitors, 1);
assert.equal(report.scoringCalibration.eligibility.byActorClass.competitor, 1);
assert.deepEqual(report.scoringCalibration.dataQuality.flags, [
  "low-eligible-competitor-count",
  "no-eligible-fills",
  "no-pnl-attribution",
]);
assert.equal(report.scoringCalibration.dataQuality.publicScoreUnchanged, true);
assert.equal(report.liquiditySummary.schemaVersion, "reef.arena.liquiditySummary.v1");
assert.equal(report.liquiditySummary.scoreNeutral, true);
assert.equal(report.liquiditySummary.pointsEffect, 0);
assert.equal(report.liquiditySummary.totals.providerCount, 3);
assert.equal(report.liquiditySummary.totals.activeProviderCount, 3);
assert.equal(report.liquiditySummary.totals.submittedLimitOrders > 0, true);
assert.equal(report.liquiditySummary.instruments.find((entry) => entry.instrumentId === "AAPL").providerCount, 3);
assert.equal(report.liquiditySummary.instruments.find((entry) => entry.instrumentId === "MSFT").providerCount, 0);
const marketMaker = report.botResults.find((entry) => entry.botId === "builtin-mm-simple");
assert.equal(marketMaker.scoreBreakdown.schemaVersion, "reef.arena.scoreBreakdown.v1");
assert.equal(marketMaker.scoreBreakdown.formulaVersion, "shadow-score-v1");
assert.equal(marketMaker.scoreBreakdown.scoreEligible, false);
assert.equal(marketMaker.scoreBreakdown.scoreEffect, "diagnostic-only");
assert.equal(marketMaker.scoreBreakdown.publicScore, null);
assert.equal(marketMaker.scoreBreakdown.shadowScore, null);
assert.equal(marketMaker.liquidityDiagnostics.schemaVersion, "reef.arena.liquidityProviderDiagnostics.v1");
assert.equal(marketMaker.liquidityDiagnostics.scoreNeutral, true);
assert.equal(marketMaker.liquidityDiagnostics.pointsEffect, 0);
assert.deepEqual(marketMaker.liquidityDiagnostics.instruments, ["AAPL"]);
assert.equal(marketMaker.liquidityDiagnostics.quoteQuality.attribution, "market-wide-proxy");
assert.equal(marketMaker.liquidityDiagnostics.providerQuoteQuality.schemaVersion, "reef.arena.providerQuoteQuality.v1");
assert.equal(marketMaker.liquidityDiagnostics.providerQuoteQuality.source, "unavailable");
assert.equal(marketMaker.liquidityDiagnostics.attribution.schemaVersion, "reef.arena.liquidityProviderAttribution.v1");
assert.equal(marketMaker.liquidityDiagnostics.attribution.source, "dry-run-trading-metrics");
assert.equal(marketMaker.liquidityDiagnostics.attribution.pointsEffect, 0);
assert.equal(marketMaker.liquidityDiagnostics.adverseSelection.available, false);
const npc = report.botResults.find((entry) => entry.botId === "builtin-npc-momentum");
assert.equal(npc.scoreBreakdown.scoreEligible, false);
assert.equal(npc.scoreBreakdown.scoreEffect, "difficulty-bucket");
assert.equal(npc.scoreBreakdown.scoringMode, "difficulty-context-only");
const competitor = report.botResults.find((entry) => entry.botId === "custom-technical-indicator");
assert.equal(competitor.scoreBreakdown.scoreEligible, true);
assert.equal(competitor.scoreBreakdown.publicScore, competitor.score);
assert.equal(Number.isFinite(competitor.scoreBreakdown.shadowScore), true);
assert.equal(competitor.scoreBreakdown.components.baseline, 1_000_000);
assert.equal(Number.isFinite(competitor.scoreBreakdown.diagnostics.fillRatio), true);
assert.equal(Number.isFinite(competitor.scoreBreakdown.diagnostics.completionRate), true);
assert.equal(Number.isFinite(competitor.scoreBreakdown.componentDetails.marketInteraction.completionScore), true);
assert.deepEqual(competitor.scoreBreakdown.diagnostics.npcDifficultyBuckets, ["benign-noise"]);

const compactResult = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--duration-seconds=1",
    "--tick-interval-ms=500",
    "--report-shape=compact",
    `--out=${compactReportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(compactResult.status, 0, `${compactResult.stdout}\n${compactResult.stderr}`);
const compactReport = JSON.parse(readFileSync(compactReportPath, "utf8"));
assert.equal(compactReport.schemaVersion, "reef.arena.localTickRun.v0");
assert.equal(compactReport.reportShape, "compact");
assert.equal(compactReport.status, "completed");
assert.equal(compactReport.totals.ticks, 16);
assert.equal(compactReport.pacingSummary.schemaVersion, "reef.arena.pacingSummary.v0");
assert.equal(compactReport.pacingSummary.enabled, false);
assert.equal(compactReport.pacingSummary.scheduledEventCount, 4);
assert.equal(compactReport.policyEnvelopeHash, report.policyEnvelopeHash);
assert.equal(compactReport.policyEnvelope.schemaVersion, "reef.arena.policyEnvelope.v1");
assert.equal(compactReport.runPlan.actorProfiles.schemaVersion, "reef.arena.actorProfileSummary.v1");
assert.equal(compactReport.runPlan.actorProfiles.byActorClass.house_market_maker, 3);
assert.equal(compactReport.botResults[0].conductMetrics.schemaVersion, "reef.arena.conductMetrics.v0");
assert.equal(compactReport.liquiditySummary.schemaVersion, "reef.arena.liquiditySummary.v1");
assert.equal(compactReport.liquiditySummary.totals.providerCount, 3);
assert.equal(compactReport.scoringCalibration.schemaVersion, "reef.arena.scoringCalibration.v1");
assert.equal(compactReport.scoringCalibration.scoreDistribution.shadowScore.count, 1);
const compactMarketMaker = compactReport.botResults.find((entry) => entry.botId === "builtin-mm-simple");
assert.equal(compactMarketMaker.liquidityDiagnostics.schemaVersion, "reef.arena.liquidityProviderDiagnostics.v1");
assert.equal(compactMarketMaker.liquidityDiagnostics.pointsEffect, 0);
assert.equal(compactMarketMaker.liquidityDiagnostics.attribution.pointsEffect, 0);
const compactCompetitor = compactReport.botResults.find((entry) => entry.botId === "custom-technical-indicator");
assert.equal(compactCompetitor.scoreBreakdown.schemaVersion, "reef.arena.scoreBreakdown.v1");
assert.equal(compactCompetitor.scoreBreakdown.formulaVersion, "shadow-score-v1");
assert.equal(Number.isFinite(compactCompetitor.scoreBreakdown.diagnostics.completionRate), true);
assert.equal(compactReport.sessionReports, undefined);
assert.equal(compactReport.healthSamples, undefined);
assert.equal(compactReport.omitted.sessionReports, 5);
assert.equal(compactReport.omitted.healthSamples, 2);
assert.equal(compactReport.latencySummary.tickElapsedMs.count, 16);
assert.deepEqual(compactReport.commandStatusSummary.rejectedByCode, {});
assert.deepEqual(compactReport.commandStatusSummary.rejectedByBotId, {});
assert.equal(compactReport.marketQualitySummary.schemaVersion, "reef.arena.marketQualitySummary.v0");
assert.ok(compactReport.marketQualitySummary.instruments.some((instrument) => instrument.instrumentId === "AAPL"));

const pacedStartedAt = Date.now();
const pacedResult = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--duration-seconds=1",
    "--tick-interval-ms=500",
    "--pace-ticks",
    "--projection-drain-cadence=scheduled-event",
    "--report-shape=compact",
    `--out=${pacedReportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);
const pacedWallElapsedMs = Date.now() - pacedStartedAt;

assert.equal(pacedResult.status, 0, `${pacedResult.stdout}\n${pacedResult.stderr}`);
const pacedReport = JSON.parse(readFileSync(pacedReportPath, "utf8"));
assert.equal(pacedReport.pacingSummary.enabled, true);
assert.equal(pacedReport.pacingSummary.scheduler, "absolute-offset-from-run-start");
assert.equal(pacedReport.pacingSummary.scheduledDurationMs, 1000);
assert.equal(pacedReport.pacingSummary.scheduledEventCount, 4);
assert.equal(pacedReport.projectionDrainCadence, "scheduled-event");
assert.equal(pacedReport.pacingSummary.totalSleepMs >= 700, true);
assert.equal(pacedReport.pacingSummary.totalSleepMs <= 1000, true);
assert.equal(pacedWallElapsedMs >= 700, true);
assert.equal(pacedWallElapsedMs < 2500, true);

const scoreV1Result = spawnSync(
  "bun",
  [
    "scripts/dev/arena-local-tick-run.mjs",
    "--compartment=vm",
    "--submit-mode=dry-run",
    "--duration-seconds=1",
    "--tick-interval-ms=500",
    "--scoring-policy-version=score-v1",
    "--report-shape=compact",
    `--out=${scoreV1ReportPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(scoreV1Result.status, 0, `${scoreV1Result.stdout}\n${scoreV1Result.stderr}`);
const scoreV1Report = JSON.parse(readFileSync(scoreV1ReportPath, "utf8"));
assert.equal(scoreV1Report.mode.scoringPolicyVersion, "score-v1");
assert.equal(scoreV1Report.policyEnvelope.scoringPolicyVersion, "score-v1");
assert.equal(scoreV1Report.scoringAssumptions.pnl.status, "ranked-input");
assert.equal(scoreV1Report.scoringCalibration.mode, "public-score-v1-with-shadow-calibration");
assert.equal(scoreV1Report.scoringCalibration.dataQuality.publicScoreUnchanged, false);
const scoreV1Competitor = scoreV1Report.botResults.find((entry) => entry.botId === "custom-technical-indicator");
assert.equal(scoreV1Competitor.scoreBreakdown.scoringMode, "public-score-v1");
assert.equal(scoreV1Competitor.score, scoreV1Competitor.scoreBreakdown.publicScore);
assert.equal(scoreV1Competitor.scoreBreakdown.componentDetails.publicScoreV1.formulaVersion, "score-v1-final-equity-risk-conduct");
assert.equal(scoreV1Report.leaderboard[0].score, scoreV1Competitor.score);

console.log("arena local tick report writer checks passed");
