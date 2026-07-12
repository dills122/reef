import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { buildSimulationRunExport, exportCounts, exportLatency, parseArgs } from "./export-simulation-run.mjs";

test("builds run export payload from stress report and artifact directory", async () => {
  const dir = await mkdtemp(path.join(tmpdir(), "reef-export-test-"));
  const reportPath = path.join(dir, "stream-ack-stress-rate-10000-workers-256.json");
  await writeFile(
    reportPath,
    JSON.stringify({
      sessionId: "load-1",
      startedAt: "2026-07-06T10:00:00Z",
      finishedAt: "2026-07-06T10:01:00Z",
      durationSeconds: 60,
      config: {
        RunID: "stream-ack-submit-stress",
        ScenarioID: "stream-ack-submit-stress",
        RunKind: "stress",
        SessionName: "stream-ack-submit-stress",
      },
      throughputRps: 9980.5,
      acceptedBusinessOpsRps: 9975.25,
      totalRequests: 600000,
      totalSuccess: 599000,
      totalFailures: 1000,
      statusCodes: { 202: 599000, 429: 1000 },
      latencyMs: { p50: 3.2, p95: 12.4, p99: 28.9 },
      quality: { validIntentSuccessRatePct: 99.8 },
      traceChecks: { checked: 200, pass: 200, fail: 0 },
      rejectTaxonomy: [{ code: "BACKPRESSURE", count: 1000 }],
    }),
  );
  await writeFile(path.join(dir, "stream-ack-stress-kpi.md"), "# KPI\n");

  const payload = await buildSimulationRunExport({
    reportPath,
    artifactRoot: dir,
    source: "local-test",
    gitSha: "abc123",
    profile: "10k-1m",
  });

  assert.equal(payload.runId, "load-1");
  assert.equal(payload.scenarioId, "stream-ack-submit-stress");
  assert.equal(payload.runKind, "stress");
  assert.equal(payload.profile, "10k-1m");
  assert.deepEqual(payload.counts, {
    attempted: 600000,
    accepted: 599000,
    completed: 599000,
    materialized: 0,
    projected: 0,
    failed: 1000,
  });
  assert.equal(payload.latencyMs.p95, 12.4);
  assert.equal(payload.artifacts.length, 2);
  assert.ok(payload.artifacts.every((artifact) => artifact.sha256.length === 64));
  assert.equal(payload.summary.traceChecks.pass, 200);
});

test("extracts counts and latency from KPI report shape", () => {
  const report = {
    sampleCount: 1,
    averages: {
      throughputRps: 1000,
      acceptedBusinessOpsRps: 990,
      p95LatencyMs: 11,
      p99LatencyMs: 22,
    },
    bestByThroughput: {
      rate: 1000,
      workers: 64,
      p95LatencyMs: 11,
      p99LatencyMs: 22,
    },
  };

  assert.deepEqual(exportCounts(report), {
    attempted: 0,
    accepted: 0,
    completed: 0,
    materialized: 0,
    projected: 0,
    failed: 0,
  });
  assert.deepEqual(exportLatency(report), { p50: null, p95: 11, p99: 22 });
});

test("extracts counts, latency, and summary from arena local tick report", async () => {
  const dir = await mkdtemp(path.join(tmpdir(), "reef-export-arena-test-"));
  const reportPath = path.join(dir, "arena-report.json");
  await writeFile(
    reportPath,
    JSON.stringify({
      schemaVersion: "reef.arena.localTickRun.v0",
      generatedAt: "2026-07-08T12:00:00Z",
      runId: "arena-run-1",
      status: "completed",
      mode: { modeId: "equity-sprint", version: "v1", npcDifficultyBuckets: ["benign-noise"] },
      policyEnvelopeHash: "sha256:abc123",
      policyEnvelope: {
        schemaVersion: "reef.arena.policyEnvelope.v1",
        modeId: "equity-sprint",
        modeVersion: "v1",
        scenarioId: "arena-equity-sprint-v1",
        seed: 170707,
        scoringPolicyVersion: "score-v0",
        economicPolicyVersion: "economic-v0",
        liquidityPolicyVersion: "liquidity-v0",
        backgroundFlowPolicyVersion: "background-flow-v0",
        creditPolicyVersion: "credit-v0",
        interventionPolicyVersion: "intervention-v0",
        npcDifficultyBuckets: ["benign-noise"],
      },
      runPlan: {
        actorProfiles: {
          schemaVersion: "reef.arena.actorProfileSummary.v1",
          byActorClass: { house_market_maker: 1 },
        },
      },
      runnerProfile: { compartment: "ses", submitMode: "live" },
      totals: {
        ticks: 4,
        failedTicks: 0,
        venueCommands: 8,
        submittedCommands: 8,
        completedCommands: 7,
        failedCommands: 0,
        rejectedCommands: 1,
        timedOutCommands: 0,
      },
      commandAccounting: {
        draftCommands: 8,
        submittedCommands: 8,
        terminalCommands: 8,
        accountingGap: 0,
      },
      commandStatusSummary: {
        commandCount: 8,
        timedOut: 0,
        byRoute: { "/api/v1/orders/submit": 6, "/api/v1/orders/cancel": 2 },
        byFinalStatus: { COMPLETED: 7, FAILED: 1 },
        byFirstStatus: { ACCEPTED: 8 },
      },
      healthSummary: {
        status: "pass",
        topOfBookPct: 100,
      },
      scoringCalibration: {
        schemaVersion: "reef.arena.scoringCalibration.v1",
        formulaVersion: "shadow-score-v1",
        scoringPolicyVersion: "score-v0",
        mode: "report-only-shadow-score-calibration",
        eligibility: {
          totalBots: 1,
          eligibleCompetitors: 0,
          nonScoringActors: 1,
          byActorClass: { house_market_maker: 1 },
          byScoreEffect: { "diagnostic-only": 1 },
        },
        difficultyContext: {
          npcDifficultyBuckets: ["benign-noise"],
          difficultyMultiplier: 1,
        },
        scoreDistribution: {
          publicScore: { count: 0, min: null, max: null, avg: null },
          shadowScore: { count: 0, min: null, max: null, avg: null },
          components: {
            equity: { count: 0, min: null, max: null, avg: null },
            risk: { count: 0, min: null, max: null, avg: null },
            conduct: { count: 0, min: null, max: null, avg: null },
            marketInteraction: { count: 0, min: null, max: null, avg: null },
            difficulty: { count: 0, min: null, max: null, avg: null },
          },
        },
        dataQuality: {
          flags: ["no-eligible-competitors"],
          fillCount: 0,
          pnlAvailableCount: 0,
          publicScoreMismatchCount: 0,
          publicScoreUnchanged: true,
        },
      },
      venueReadback: {
        mode: "live",
        skipped: false,
        projectionDrained: true,
        availability: {
          body: {
            projections: [{ projectionName: "runtime-normalized-venue-outcomes", projectedCount: 7, lag: 0 }],
          },
        },
      },
      botResults: [
        {
          botId: "builtin-mm-simple",
          role: "market-maker",
          finalEquity: 1000100,
          realizedPnl: 100,
          maxDrawdown: 25,
          ticksRun: 2,
          venueCommands: 4,
          failedTicks: 0,
          freezeCount: 0,
          disqualified: false,
          scoreBreakdown: {
            schemaVersion: "reef.arena.scoreBreakdown.v1",
            formulaVersion: "shadow-score-v1",
            scoringPolicyVersion: "score-v0",
            scoreEligible: false,
            actorClass: "house_market_maker",
            scoreEffect: "diagnostic-only",
            publicScore: null,
            shadowScore: null,
            scoringMode: "diagnostic-only",
            components: {
              baseline: 0,
              equity: 0,
              risk: 0,
              conduct: 0,
              marketInteraction: 0,
              difficulty: 0,
            },
            componentDetails: {
              risk: {
                inventoryExposurePenalty: 10,
              },
              marketInteraction: {
                completionScore: 5000,
              },
            },
            diagnostics: {
              finalEquity: 1000100,
              totalPnl: 100,
              grossSubmittedQuantity: 4,
              grossSubmittedNotional: 400,
              grossExecutedNotional: 200,
              filledQuantity: 2,
              fillRatio: 0.5,
              completionRate: 1,
              pnlPerExecutedNotionalBps: 5000,
              inventoryExposureRatio: 0.25,
              inventoryConcentration: 1,
              fillCount: 2,
              submittedCommands: 4,
              cancelReplaceRatio: 0,
              timeoutRate: 0,
              variableScoreBeforeDifficulty: 0,
              difficultyMultiplier: 1,
              npcDifficultyBuckets: ["benign-noise"],
            },
          },
          actorClass: "house_market_maker",
          actorProfile: {
            profileId: "mm-tight-bluechip",
            profileVersion: "v1",
            actorClass: "house_market_maker",
            difficultyBucket: "neutral-liquidity",
            scoreEffect: "diagnostic-only",
            profileHash: "sha256:mm",
          },
          tradingMetrics: {
            commands: { submitted: 4, failed: 0 },
            pnl: { realized: 100 },
          },
          conductMetrics: {
            schemaVersion: "reef.arena.conductMetrics.v0",
            status: "reported",
            orderCommands: 4,
            cancelReplaceRatio: 0,
            invalidIntentRate: 0,
            timeoutRate: 0,
            maxActionsPerTick: 2,
            maxVenueCommandsPerTick: 2,
            freezeCount: 0,
          },
        },
      ],
      settlementScore: {
        participants: [{ participantId: "builtin-mm-simple", scorePenaltyPoints: 0 }],
      },
      enforcementEvents: [],
      sessionReports: [
        {
          ticks: [
            { elapsedMs: 2 },
            { elapsedMs: 4 },
            { elapsedMs: 8 },
            { elapsedMs: 16 },
          ],
        },
      ],
    }),
  );

  const payload = await buildSimulationRunExport({
    reportPath,
    runKind: "arena-local-tick",
    profile: "equity-sprint-v1",
  });

  assert.equal(payload.runId, "arena-run-1");
  assert.equal(payload.runKind, "arena-local-tick");
  assert.deepEqual(payload.counts, {
    attempted: 8,
    accepted: 8,
    completed: 7,
    materialized: 7,
    projected: 7,
    failed: 1,
  });
  assert.deepEqual(payload.latencyMs, { p50: 4, p95: 16, p99: 16 });
  assert.equal(payload.summary.commandAccounting.accountingGap, 0);
  assert.equal(payload.summary.commandStatusSummary.byRoute["/api/v1/orders/cancel"], 2);
  assert.equal(payload.summary.healthSummary.status, "pass");
  assert.equal(payload.summary.scoringCalibration.schemaVersion, "reef.arena.scoringCalibration.v1");
  assert.equal(payload.summary.scoringCalibration.dataQuality.flags[0], "no-eligible-competitors");
  assert.equal(payload.summary.policyEnvelopeHash, "sha256:abc123");
  assert.equal(payload.summary.policyEnvelope.economicPolicyVersion, "economic-v0");
  assert.equal(payload.summary.actorProfiles.byActorClass.house_market_maker, 1);
  assert.equal(payload.summary.botResults[0].botId, "builtin-mm-simple");
  assert.equal(payload.summary.botResults[0].finalEquity, 1000100);
  assert.equal(payload.summary.botResults[0].tradingMetrics.commands.submitted, 4);
  assert.equal(payload.summary.botResults[0].actorProfile.profileId, "mm-tight-bluechip");
  assert.equal(payload.summary.botResults[0].conductMetrics.maxVenueCommandsPerTick, 2);
  assert.equal(payload.summary.botResults[0].scoreBreakdown.schemaVersion, "reef.arena.scoreBreakdown.v1");
  assert.equal(payload.summary.botResults[0].scoreBreakdown.formulaVersion, "shadow-score-v1");
  assert.equal(payload.summary.botResults[0].scoreBreakdown.scoreEffect, "diagnostic-only");
  assert.equal(payload.summary.botResults[0].scoreBreakdown.publicScore, null);
  assert.equal(payload.summary.botResults[0].scoreBreakdown.componentDetails.marketInteraction.completionScore, 5000);
  assert.equal(payload.summary.botResults[0].scoreBreakdown.diagnostics.fillRatio, 0.5);
  assert.equal(payload.summary.botResults[0].scoreBreakdown.diagnostics.difficultyMultiplier, 1);
  assert.equal(payload.summary.settlementScore.participants[0].participantId, "builtin-mm-simple");
});

test("parses cli flags", () => {
  const options = parseArgs([
    "--report",
    "report.json",
    "--artifact-root",
    "artifacts",
    "--post",
    "--api-url",
    "http://127.0.0.1:8080",
    "--run-id",
    "run-1",
  ]);

  assert.equal(options.reportPath, "report.json");
  assert.equal(options.artifactRoot, "artifacts");
  assert.equal(options.post, true);
  assert.equal(options.apiUrl, "http://127.0.0.1:8080");
  assert.equal(options.runId, "run-1");
});
