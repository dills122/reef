import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const dir = mkdtempSync(join(tmpdir(), "reef-arena-local-hardening-summary-"));
const reportPath = join(dir, "compact-report.json");
const summaryPath = join(dir, "summary.json");

writeFileSync(reportPath, JSON.stringify({
  schemaVersion: "reef.arena.localTickRun.v0",
  reportShape: "compact",
  startedAt: "2026-07-09T00:00:00.000Z",
  completedAt: "2026-07-09T00:01:00.000Z",
  runId: "compact-hardening-test",
  mode: {
    modeId: "equity-multi-local",
    scoringPolicyVersion: "score-v0",
    riskPolicyVersion: "arena-risk-v0",
  },
  runPlan: {
    durationSeconds: 60,
    selectedBotCount: 2,
    schedulingMode: "shared-arena-time",
    totalTickCount: 120,
  },
  status: "completed",
  elapsedMs: 1200,
  totals: {
    ticks: 120,
    failedTicks: 0,
    submittedCommands: 55,
    completedCommands: 50,
    rejectedCommands: 5,
    timedOutCommands: 0,
  },
  commandStatusSummary: {
    commandCount: 55,
    timedOut: 0,
    byRoute: {
      "/api/v1/orders/submit": 43,
      "/api/v1/orders/cancel": 12,
    },
    byFinalStatus: {
      COMPLETED: 50,
      REJECTED: 5,
    },
    rejectedByCode: {
      SELF_TRADE_PREVENTION: 5,
    },
    rejectedByBotId: {
      "builtin-npc-taker-aapl": 5,
    },
    avgIntakeElapsedMs: 3,
    avgStatusElapsedMs: 1,
  },
  latencySummary: {
    tickElapsedMs: {
      count: 120,
      p50: 1,
      p95: 2,
      p99: 3,
      max: 4,
    },
  },
  activityBySchedulingClass: {
    house_responsive: {
      botCount: 1,
      ticks: 60,
      submittedCommands: 40,
      completedCommands: 40,
      rejectedCommands: 0,
      timedOutCommands: 0,
      dataCalls: 60,
    },
    npc_tick: {
      botCount: 1,
      ticks: 60,
      submittedCommands: 15,
      completedCommands: 10,
      rejectedCommands: 5,
      timedOutCommands: 0,
      dataCalls: 60,
    },
  },
  healthSummary: {
    status: "pass",
    failures: [],
    topOfBookPct: 100,
    depthPct: 100,
    medianQuotedSpreadBps: 20,
    p95QuotedSpreadBps: 25,
    crossedBookCount: 0,
    emptyBookCount: 0,
  },
  marketQualitySummary: {
    schemaVersion: "reef.arena.marketQualitySummary.v0",
    status: "pass",
    failures: [],
    instruments: [
      {
        instrumentId: "AAPL",
        status: "pass",
        failures: [],
        sampleCount: 60,
        topOfBookPct: 100,
        depthPct: 100,
        medianQuotedSpreadBps: 20,
        p95QuotedSpreadBps: 25,
        crossedBookCount: 0,
      },
    ],
  },
  venueReadback: {
    projectionDrained: true,
    ownOrders: [
      { botId: "builtin-mm-lifecycle-safe", current: { body: { orders: [{ orderId: "o1" }] } } },
    ],
  },
  enforcementEvents: [],
  botResults: [
    {
      botId: "builtin-mm-lifecycle-safe",
      displayName: "Twin Sun Liquidity",
      role: "market-maker",
      schedulingClass: "house_responsive",
      tradingMetrics: {
        commands: {
          submittedByRoute: {
            "/api/v1/orders/submit": 28,
            "/api/v1/orders/cancel": 12,
          },
        },
      },
    },
    {
      botId: "builtin-npc-taker-aapl",
      displayName: "AAPL Rogue Taker",
      role: "npc",
      schedulingClass: "npc_tick",
      tradingMetrics: {
        commands: {
          submittedByRoute: {
            "/api/v1/orders/submit": 15,
          },
        },
      },
    },
  ],
  omitted: {
    sessionReports: 2,
    healthSamples: 60,
  },
}));

const result = spawnSync(
  "node",
  [
    "scripts/dev/arena-local-hardening-run.mjs",
    `--input-report=${reportPath}`,
    `--summary-out=${summaryPath}`,
  ],
  {
    cwd: repoRoot,
    encoding: "utf8",
    env: {
      ...process.env,
      ORDER_LIFECYCLE_PROJECTOR_ENABLED: "true",
      MARKET_DATA_PROJECTOR_ENABLED: "true",
    },
  },
);

assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
const summary = JSON.parse(readFileSync(summaryPath, "utf8"));
assert.equal(summary.status, "pass");
assert.equal(summary.commands.rejects.count, 5);
assert.equal(summary.commands.rejects.byCode.SELF_TRADE_PREVENTION, 5);
assert.equal(summary.commandPressure.totals.cancelCommands, 12);
assert.equal(summary.commandPressure.totals.houseCommands, 40);
assert.equal(summary.latency.source, "compact-report-aggregates");
assert.equal(summary.marketQuality.source, "compact-report-market-quality-summary");
assert.equal(summary.marketQuality.byInstrument[0].instrumentId, "AAPL");

console.log("arena local hardening compact summary checks passed");
