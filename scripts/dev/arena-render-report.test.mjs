import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-render-report-"));
const reportPath = join(dir, "report.json");
const htmlPath = join(dir, "report.html");

writeFileSync(
  reportPath,
  JSON.stringify({
    schemaVersion: "reef.arena.localTickRun.v0",
    generatedAt: "2026-07-08T00:00:00.000Z",
    runId: "arena-test-run",
    status: "completed_with_freezes",
    mode: {
      modeId: "equity-sprint",
      seed: 170707,
      scoringPolicyVersion: "score-v0",
      riskPolicyVersion: "arena-risk-v0",
    },
    runnerProfile: { submitMode: "live" },
    scoringAssumptions: {
      scoringPolicyVersion: "score-v0",
      scoreBasis: "participation-and-policy-compliance",
      leaderboardScope: "score-eligible public competitor bots only",
      houseBots: "diagnostics-only",
      pnl: { status: "not-yet-scored" },
      tradingMetrics: { status: "command-mix v0" },
    },
    totals: {
      ticks: 3,
      failedTicks: 1,
      venueCommands: 12,
      submittedCommands: 12,
      rejectedCommands: 1,
      timedOutCommands: 0,
      dataCalls: 7,
    },
    commandAccounting: {
      accountingGap: 0,
      terminalCommands: 12,
    },
    marketQualitySummary: {
      schemaVersion: "reef.arena.marketQualitySummary.v0",
      status: "warn",
      instruments: [
        {
          instrumentId: "AAPL",
          status: "warn",
          failures: ["p95QuotedSpreadBps 60.00 > 50"],
          topOfBookPct: 100,
          depthPct: 100,
          medianQuotedSpreadBps: 20,
          p95QuotedSpreadBps: 60,
          crossedBookCount: 0,
        },
      ],
    },
    executionSummary: {
      schemaVersion: "reef.arena.executionSummary.v0",
      source: "venue-readback-order-fills",
      fillCount: 2,
      filledQuantity: 4,
      filledNotional: 402,
      avgFillPrice: 100.5,
      byInstrument: {
        AAPL: { fillCount: 2, filledQuantity: 4, filledNotional: 402, avgFillPrice: 100.5 },
      },
    },
    venueReadback: {
      projectionDrained: true,
    },
    persistence: {
      enabled: true,
      skipped: false,
      operations: [{ ok: true }],
      rawResults: { statusCode: 200 },
      rawEnforcementEvents: { statusCode: 200 },
      leaderboard: { statusCode: 200 },
      leaderboardEntry: { botId: "custom-technical-indicator", rank: 1 },
    },
    leaderboard: [
      {
        rank: 1,
        botId: "custom-technical-indicator",
        displayName: "Twin Ion Quant",
        versionId: "local",
        score: 1000450,
        venueCommands: 5,
        disqualified: false,
      },
    ],
    botResults: [
      {
        botId: "custom-technical-indicator",
        displayName: "Twin Ion Quant",
        versionId: "local",
        score: 1000450,
        actionsProposed: 5,
        venueCommands: 5,
        dataCalls: 3,
        scoreEligible: true,
        disqualified: false,
        tradingMetrics: {
          orderFlow: {
            submittedLimitOrders: 4,
            cancelCommands: 1,
            buyQuantity: 2,
            sellQuantity: 2,
            grossSubmittedNotional: 400.5,
          },
          executions: {
            fillCount: 1,
          },
          inventory: {
            netQuantityByInstrument: {
              AAPL: 1,
            },
          },
          pnl: { available: true, total: 0.5 },
        },
      },
      {
        botId: "custom-too-many-orders",
        displayName: "Thermal Exhaust Stressor",
        versionId: "local",
        score: 750000,
        actionsProposed: 20,
        venueCommands: 0,
        dataCalls: 0,
        scoreEligible: true,
        disqualified: true,
        tradingMetrics: {
          orderFlow: {
            submittedLimitOrders: 0,
            cancelCommands: 0,
            buyQuantity: 0,
            sellQuantity: 0,
            grossSubmittedNotional: 0,
          },
          pnl: { available: false },
        },
      },
    ],
    enforcementEvents: [
      {
        botId: "custom-too-many-orders",
        versionId: "local",
        decision: "freeze",
        reasonCode: "tick_policy_violation",
        reason: "maxActionsPerTick 20 > 5",
        policyVersion: "arena-risk-v0",
        occurredAt: "2026-07-08T00:00:01.000Z",
      },
    ],
  }),
);

const result = spawnSync(
  "node",
  [
    "scripts/dev/arena-render-report.mjs",
    `--report=${reportPath}`,
    `--out=${htmlPath}`,
  ],
  { cwd: repoRoot, encoding: "utf8" },
);

assert.equal(result.status, 0, result.stderr);
const html = readFileSync(htmlPath, "utf8");
assert.match(html, /Reef Arena Operator Report/);
assert.match(html, /arena-test-run/);
assert.match(html, /Twin Ion Quant/);
assert.match(html, /Thermal Exhaust Stressor/);
assert.match(html, /Trading Metrics/);
assert.match(html, /Market Quality/);
assert.match(html, /Execution Summary/);
assert.match(html, /TOTAL/);
assert.match(html, /100.5/);
assert.match(html, /p95QuotedSpreadBps 60.00 &gt; 50/);
assert.match(html, /Scoring Assumptions/);
assert.match(html, /0.5/);
assert.match(html, /custom-technical-indicator/);
assert.match(html, /custom-too-many-orders/);
assert.match(html, /maxActionsPerTick 20 &gt; 5/);
assert.match(html, /projection drained/);

console.log("arena report render checks passed");
