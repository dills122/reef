import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const repoRoot = new URL("../../", import.meta.url).pathname;
const fixture = JSON.parse(readFileSync(join(repoRoot, "packages/bot-sdk/fixtures/aapl-multi-tick.json"), "utf8"));
const { runBotStrategyScenarioV1 } = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/src/strategy-runner.ts")).href);
const { createRecordingVenueTransportV1 } = await import(
  pathToFileURL(join(repoRoot, "packages/bot-sdk/src/venue-client.ts")).href
);

await assertMultiSymbolStrategyRun();

console.log("bot SDK strategy runner checks passed");

async function assertMultiSymbolStrategyRun() {
  const module = await import(pathToFileURL(join(repoRoot, "packages/bot-sdk/examples/multi-symbol-strategy-bot.ts")).href);
  const transport = createRecordingVenueTransportV1(202);
  const report = await runBotStrategyScenarioV1({
    BotClass: module.default,
    fixture: {
      ...fixture,
      botId: "multi-symbol-strategy",
      actorId: "actor-multi-symbol-strategy",
      historicalBars: {
        AAPL: bars("AAPL", [
          100, 100, 100, 100, 100, 100, 100, 100, 100, 100,
          100, 100, 100, 100, 100, 80, 85, 90, 92, 95,
        ]),
        MSFT: bars("MSFT", [
          200, 200, 200, 200, 200, 200, 200, 200, 200, 200,
          200, 200, 200, 200, 200, 220, 215, 212, 210, 205,
        ]),
      },
      ticks: fixture.ticks.map((tick) => ({
        ...tick,
        marketSnapshots: {
          ...tick.marketSnapshots,
          MSFT: {
            instrumentId: "MSFT",
            asOf: tick.occurredAt,
            bidPrice: 211,
            askPrice: 213,
            midPrice: 212,
            lastPrice: 212,
          },
          NVDA: {
            instrumentId: "NVDA",
            asOf: tick.occurredAt,
            bidPrice: 499,
            askPrice: 501,
            midPrice: 500,
            lastPrice: 500,
          },
          TSLA: {
            instrumentId: "TSLA",
            asOf: tick.occurredAt,
            bidPrice: 249,
            askPrice: 251,
            midPrice: 250,
            lastPrice: 250,
          },
        },
      })),
    },
    venueTransport: transport,
  });

  assert.equal(report.status, "completed");
  assert.equal(report.ticksRun, 3);
  assert.equal(report.signalsGenerated, 6);
  assert.equal(report.orderActionsProposed, 6);
  assert.equal(report.dataCalls, 6);
  assert.equal(report.ticks[0].signals.length, 2);
  assert.equal(report.ticks[0].venueCommands.length, 2);
  assert.equal(report.ticks[0].venueCommands[0].route, "/api/v1/orders/submit");
  assert.equal(transport.requests.length, 6);
}

function bars(instrumentId, closes) {
  return closes.map((close, index) => ({
    instrumentId,
    start: `2026-07-04T14:${String(index).padStart(2, "0")}:00.000Z`,
    end: `2026-07-04T14:${String(index + 1).padStart(2, "0")}:00.000Z`,
    open: close,
    high: close + 1,
    low: close - 1,
    close,
    volume: 1000 + index,
  }));
}
